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
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Carrier Pinning Simulator for Virtual Threads (Project Loom).
 * 
 * Forces virtual threads to pin to carrier threads by executing synchronized blocks
 * or JNI-like native calls. This simulates the performance degradation that occurs
 * when virtual threads cannot be unmounted due to synchronized blocks or native frames.
 * 
 * Targets: java.lang.Thread, java.lang.VirtualThread, java.util.concurrent.Executor,
 *          ForkJoinPool, and common Spring async entry points.
 */
public class CarrierPinningInterceptor {

    private static final List<TargetSpec> TARGETS = Arrays.asList(
        // Virtual Thread execution paths
        new TargetSpec(
            "java.lang.Thread",
            new String[]{"start", "run"},
            "Thread"
        ),
        new TargetSpec(
            "java.util.concurrent.ForkJoinPool",
            new String[]{"execute", "submit", "invoke"},
            "ForkJoinPool"
        ),
        new TargetSpec(
            "java.util.concurrent.Executor",
            new String[]{"execute"},
            "Executor"
        ),
        // Spring async entry points
        new TargetSpec(
            "org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor",
            new String[]{"postProcessAfterInitialization"},
            "SpringAsync"
        ),
        // Common thread pool executors
        new TargetSpec(
            "java.util.concurrent.ThreadPoolExecutor",
            new String[]{"execute", "submit"},
            "ThreadPoolExecutor"
        )
    );

    private static final Random RANDOM = new Random();

    public static void install(Instrumentation inst) {
        ElementMatcher.Junction<TypeDescription> typeMatcher = buildTypeMatcher();

        AgentBuilder.Transformer transformer = (builder, typeDescription, classLoader, module, protectionDomain) -> {
            String className = typeDescription.getName();
            TargetSpec spec = findSpec(className);
            if (spec == null) return builder;

            ElementMatcher<net.bytebuddy.description.method.MethodDescription> methodMatcher = 
                ElementMatchers.namedOneOf(spec.methodNames);

            System.out.println("[ChaosAgent] Transforming for carrier pinning: " + spec.displayName + " (" + className + ")");
            return builder.visit(Advice.to(PinningAdvice.class).on(methodMatcher));
        };

        new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith("com.chaosagent."))
            .disableClassFormatChanges()
            .type(typeMatcher)
            .transform(transformer)
            .installOn(inst);

        // Retransform already loaded classes
        retransformLoadedClasses(inst);

        System.out.println("[ChaosAgent] Carrier pinning interceptors installed");
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
                        System.out.println("[ChaosAgent] Retransformed for pinning: " + name);
                    } catch (UnmodifiableClassException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Error retransforming classes for pinning: " + e.getMessage());
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
     * Advice that forces carrier thread pinning by executing a synchronized block
     * with a configurable probability. This simulates the "pinning" behavior where
     * virtual threads cannot be unmounted from their carrier threads.
     */
    public static class PinningAdvice {
        // Use a simple object for synchronization to force pinning
        private static final Object PINNING_LOCK = new Object();
        
        // Thread-local counter to track pinning events per thread
        private static final ThreadLocal<Long> pinCount = ThreadLocal.withInitial(() -> 0L);

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Origin("#m") String methodName) {
            if (!ChaosAgent.ChaosConfig.pinningEnabled) return;
            
            // Check probability - only pin a fraction of calls
            double prob = ChaosAgent.ChaosConfig.pinningProbability;
            if (prob <= 0 || ThreadLocalRandom.current().nextDouble() > prob) {
                return;
            }

            // Execute synchronized block to force carrier pinning
            // This is the key: synchronized blocks pin virtual threads to carrier threads
            synchronized (PINNING_LOCK) {
                // Simulate some work while pinned - busy spin for measurable time
                // Increased iterations to ensure measurable delay on modern CPUs
                long iterations = 2_000_000 + ThreadLocalRandom.current().nextInt(3_000_000);
                long sum = 0;
                for (long i = 0; i < iterations; i++) {
                    sum += i;
                }
                // Prevent optimization
                pinCount.set(pinCount.get() + sum);
            }
        }
    }
}