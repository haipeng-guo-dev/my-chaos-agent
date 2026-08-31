package com.chaosagent.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;

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

    public static void install(Instrumentation inst) {
        ElementMatcher.Junction<TypeDescription> typeMatcher = buildTypeMatcher();

        AgentBuilder.Transformer transformer = (builder, typeDescription, classLoader, module, protectionDomain) -> {
            String className = typeDescription.getName();
            TargetSpec spec = findSpec(className);
            if (spec == null) return builder;

            ElementMatcher<net.bytebuddy.description.method.MethodDescription> methodMatcher = 
                ElementMatchers.namedOneOf(spec.methodNames);

            System.out.println("[ChaosAgent] Transforming for CPU backpressure: " + spec.displayName + " (" + className + ")");
            return builder.visit(Advice.to(BootstrapCpuBackpressureAdvice.class).on(methodMatcher));
        };

        new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith("com.chaosagent."))
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                    System.err.println("[ChaosAgent] CPU backpressure transform failed for " + typeName + ": " + throwable);
                }
            })
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

    // Debug: log when transformer is applied at class load time
    private static final AgentBuilder.Transformer DEBUG_TRANSFORMER = (builder, typeDescription, classLoader, module, protectionDomain) -> {
        String className = typeDescription.getName();
        TargetSpec spec = findSpec(className);
        if (spec != null) {
            System.out.println("[ChaosAgent] CpuBackpressureInterceptor: Transforming at load time: " + spec.displayName + " (" + className + ")");
        }
        return builder;
    };

    private static void retransformLoadedClasses(Instrumentation inst) {
        try {
            Class<?>[] loadedClasses = inst.getAllLoadedClasses();
            System.out.println("[ChaosAgent] CpuBackpressureInterceptor: Checking " + loadedClasses.length + " loaded classes for retransform");
            int matched = 0, modifiable = 0, retransformed = 0;
            for (Class<?> clazz : loadedClasses) {
                String name = clazz.getName();
                if (findSpec(name) != null) {
                    matched++;
                    boolean isModifiable = inst.isModifiableClass(clazz);
                    if (isModifiable) {
                        modifiable++;
                        try {
                            inst.retransformClasses(clazz);
                            retransformed++;
                            System.out.println("[ChaosAgent] CpuBackpressureInterceptor: Retransformed " + name);
                        } catch (UnmodifiableClassException ignored) {
                            System.out.println("[ChaosAgent] CpuBackpressureInterceptor: UnmodifiableClassException for " + name);
                        } catch (Exception e) {
                            System.out.println("[ChaosAgent] CpuBackpressureInterceptor: Error retransforming " + name + ": " + e.getMessage());
                        }
                    } else {
                        System.out.println("[ChaosAgent] CpuBackpressureInterceptor: NOT modifiable: " + name);
                    }
                }
            }
            System.out.println("[ChaosAgent] CpuBackpressureInterceptor: Matched=" + matched + ", Modifiable=" + modifiable + ", Retransformed=" + retransformed);
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Error retransforming classes for CPU backpressure: " + e.getMessage());
            e.printStackTrace();
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
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter(@Advice.Origin("#m") String methodName) {
            if (CpuBackpressureAdvice.class.getClassLoader() == null) {
                return BootstrapStressState.applyCpuBackpressure();
            } else if (ChaosAgent.ChaosConfig.cpuBackpressureEnabled) {
                BootstrapStressStateAccess.configure(false, 0, true, ChaosAgent.ChaosConfig.cpuBackpressureIntensity);
                return BootstrapStressState.applyCpuBackpressure();
            }
            return 0L;
        }

        public static long getTotalBusySpinNanos() {
            return BootstrapStressStateAccess.busyNanos();
        }

        public static long getBusySpinCount() {
            return BootstrapStressStateAccess.busyCount();
        }

        public static void resetMetrics() {
            BootstrapStressStateAccess.resetCpuMetrics();
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
