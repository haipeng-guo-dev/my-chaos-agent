package com.chaosagent.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU Thermal Backpressure Simulator.
 * 
 * Injects algorithmic busy-spin computations to simulate CPU thermal throttling,
 * container CPU limits, or noisy neighbor scenarios. The busy-spin runs on the
 * carrier thread, directly impacting virtual thread scheduling and application throughput.
 * 
 * Targets: High-frequency execution paths - loop bodies, stream operations,
 *          ForkJoinTask, CompletableFuture, Reactor/Flux operators.
 */
public class CpuBackpressureInterceptor {

    private static final List<TargetSpec> TARGETS = Arrays.asList(
        // ForkJoinPool task execution (carrier threads for virtual threads)
        new TargetSpec(
            "java.util.concurrent.ForkJoinTask",
            new String[]{"exec", "doExec", "compute"},
            "ForkJoinTask"
        ),
        new TargetSpec(
            "java.util.concurrent.ForkJoinPool",
            new String[]{"runWorker", "scan", "helpComplete"},
            "ForkJoinPool"
        ),
        // CompletableFuture async execution
        new TargetSpec(
            "java.util.concurrent.CompletableFuture",
            new String[]{"uniApply", "uniAccept", "uniCompose", "biApply", "biAccept"},
            "CompletableFuture"
        ),
        // Stream pipeline execution
        new TargetSpec(
            "java.util.stream.ReferencePipeline",
            new String[]{"forEach", "forEachOrdered", "reduce", "collect", "count"},
            "StreamPipeline"
        ),
        // Reactor (if present) - common in Spring WebFlux
        new TargetSpec(
            "reactor.core.publisher.Flux",
            new String[]{"subscribe", "blockFirst", "blockLast", "collectList"},
            "ReactorFlux"
        ),
        new TargetSpec(
            "reactor.core.publisher.Mono",
            new String[]{"subscribe", "block", "blockOptional"},
            "ReactorMono"
        ),
        // Spring task execution
        new TargetSpec(
            "org.springframework.core.task.TaskExecutor",
            new String[]{"execute", "submit"},
            "SpringTaskExecutor"
        )
    );

    // Metrics
    private static final AtomicLong TOTAL_BUSY_SPIN_NANOS = new AtomicLong(0);
    private static final AtomicLong BUSY_SPIN_COUNT = new AtomicLong(0);

    public static void install(Instrumentation inst) {
        ElementMatcher.Junction<TypeDescription> typeMatcher = buildTypeMatcher();

        AgentBuilder.Transformer transformer = (builder, typeDescription, classLoader, module, protectionDomain) -> {
            String className = typeDescription.getName();
            TargetSpec spec = findSpec(className);
            if (spec == null) return builder;

            ElementMatcher<net.bytebuddy.description.method.MethodDescription> methodMatcher = 
                ElementMatchers.namedOneOf(spec.methodNames);

            System.out.println("[ChaosAgent] Transforming for CPU backpressure: " + spec.displayName + " (" + className + ")");
            return builder.visit(Advice.to(CpuBackpressureAdvice.class).on(methodMatcher));
        };

        new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith("com.chaosagent."))
            .disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemOut())
            .with(AgentBuilder.InstallationListener.StreamWriting.toSystemOut())
            .type(typeMatcher)
            .transform(transformer)
            .installOn(inst);

        retransformLoadedClasses(inst);
        System.out.println("[ChaosAgent] CPU backpressure interceptors installed");
    }

    private static ElementMatcher.Junction<TypeDescription> buildTypeMatcher() {
        ElementMatcher.Junction<TypeDescription> matcher = ElementMatchers.none();
        for (TargetSpec spec : TARGETS) {
            matcher = matcher.or(ElementMatchers.named(spec.className));
        }
        return matcher;
    }

    private static TargetSpec findSpec(String className) {
        for (TargetSpec spec : TARGETS) {
            if (spec.className.equals(className)) return spec;
        }
        return null;
    }

    private static void retransformLoadedClasses(Instrumentation inst) {
        try {
            Class<?>[] loadedClasses = inst.getAllLoadedClasses();
            for (Class<?> clazz : loadedClasses) {
                String name = clazz.getName();
                if (findSpec(name) != null && inst.isModifiableClass(clazz)) {
                    try {
                        inst.retransformClasses(clazz);
                    } catch (UnmodifiableClassException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Error retransforming classes for CPU backpressure: " + e.getMessage());
        }
    }

    private static class TargetSpec {
        final String className;
        final String[] methodNames;
        final String displayName;

        TargetSpec(String className, String[] methodNames, String displayName) {
            this.className = className;
            this.methodNames = methodNames;
            this.displayName = displayName;
        }
    }

    /**
     * Advice that performs busy-spin computation to simulate CPU pressure.
     * The intensity (0-100) controls how much CPU time is consumed per interception.
     * This runs on the carrier thread, directly affecting virtual thread scheduling.
     */
    public static class CpuBackpressureAdvice {
        // Pre-computed prime numbers for busy-spin work
        private static final int[] PRIMES = new int[10000];
        static {
            int count = 0;
            for (int i = 2; count < PRIMES.length; i++) {
                boolean isPrime = true;
                for (int j = 2; j * j <= i; j++) {
                    if (i % j == 0) { isPrime = false; break; }
                }
                if (isPrime) PRIMES[count++] = i;
            }
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter(@Advice.Origin("#m") String methodName) {
            if (!ChaosAgent.ChaosConfig.cpuBackpressureEnabled) return 0L;
            
            int intensity = ChaosAgent.ChaosConfig.cpuBackpressureIntensity;
            if (intensity <= 0) return 0L;

            // Only apply to a subset of calls based on intensity
            // Intensity 100 = every call, 50 = ~50% of calls, 10 = ~10% of calls
            if (ThreadLocalRandom.current().nextInt(100) >= intensity) {
                return 0L;
            }

            long startNanos = System.nanoTime();
            
            // Busy-spin: perform CPU-intensive but deterministic work
            // Duration scales with intensity: 1000 - 100000 iterations
            int iterations = 1000 + (intensity * 1000);
            
            // Use volatile to prevent JIT optimization of the loop
            long result = 0;
            for (int i = 0; i < iterations; i++) {
                // Prime factorization-like work - hard to optimize away
                int prime = PRIMES[i % PRIMES.length];
                result ^= prime * (i + 1);
                // Modulo and bitwise ops - CPU intensive
                result = (result * 31) ^ (result >>> 16);
            }
            
            // Prevent dead code elimination
            if (result == Long.MIN_VALUE) {
                // Never happens, but compiler doesn't know that
                System.out.println("chaos-cpu-result: " + result);
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            TOTAL_BUSY_SPIN_NANOS.addAndGet(elapsedNanos);
            BUSY_SPIN_COUNT.incrementAndGet();
            
            return elapsedNanos;
        }

        public static long getTotalBusySpinNanos() {
            return TOTAL_BUSY_SPIN_NANOS.get();
        }

        public static long getBusySpinCount() {
            return BUSY_SPIN_COUNT.get();
        }

        public static void resetMetrics() {
            TOTAL_BUSY_SPIN_NANOS.set(0);
            BUSY_SPIN_COUNT.set(0);
        }
    }

    // Static getters for metrics exposure
    public static long getTotalBusyNanos() {
        return CpuBackpressureAdvice.getTotalBusySpinNanos();
    }

    public static long getBusyCount() {
        return CpuBackpressureAdvice.getBusySpinCount();
    }

    public static void resetMetrics() {
        CpuBackpressureAdvice.resetMetrics();
    }
}