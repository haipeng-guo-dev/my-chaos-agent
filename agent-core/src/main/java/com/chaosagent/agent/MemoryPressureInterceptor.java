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
 * Managed Memory Pressure Simulator.
 * 
 * Creates controlled memory retention scenarios using ThreadLocal and ConcurrentHashMap
 * with safety caps to trigger GC pressure and potential OOM conditions without crashing
 * the host application. This simulates memory leaks, cache bloat, and heap pressure.
 * 
 * Targets: Common allocation hotspots - StringBuilder, ArrayList, HashMap, ByteBuffer,
 *          Spring request/response processing, JSON serialization.
 */
public class MemoryPressureInterceptor {

    private static final List<TargetSpec> TARGETS = Arrays.asList(
        // Collection allocation hotspots
        new TargetSpec(
            "java.util.ArrayList",
            new String[]{"add", "addAll", "ensureCapacity"},
            "ArrayList"
        ),
        new TargetSpec(
            "java.util.HashMap",
            new String[]{"put", "putAll", "computeIfAbsent"},
            "HashMap"
        ),
        new TargetSpec(
            "java.lang.StringBuilder",
            new String[]{"append", "ensureCapacity"},
            "StringBuilder"
        ),
        new TargetSpec(
            "java.nio.ByteBuffer",
            new String[]{"allocate", "allocateDirect", "wrap"},
            "ByteBuffer"
        ),
        // Spring request processing
        new TargetSpec(
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor",
            new String[]{"readWithMessageConverters", "writeWithMessageConverters"},
            "SpringMvc"
        ),
        // JSON serialization (Jackson)
        new TargetSpec(
            "com.fasterxml.jackson.databind.ObjectMapper",
            new String[]{"writeValue", "writeValueAsString", "readValue"},
            "Jackson"
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

            System.out.println("[ChaosAgent] Transforming for memory pressure: " + spec.displayName + " (" + className + ")");
            return builder.visit(Advice.to(BootstrapMemoryPressureAdvice.class).on(methodMatcher));
        };

        new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith("com.chaosagent."))
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .with(new AgentBuilder.Listener.Adapter() {
                @Override
                public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                    System.err.println("[ChaosAgent] Memory pressure transform failed for " + typeName + ": " + throwable);
                }
            })
            .type(typeMatcher)
            .transform(transformer)
            .installOn(inst);

        retransformLoadedClasses(inst);
        System.out.println("[ChaosAgent] Memory pressure interceptors installed");
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
            System.out.println("[ChaosAgent] MemoryPressureInterceptor: Transforming at load time: " + spec.displayName + " (" + className + ")");
        }
        return builder;
    };

    private static void retransformLoadedClasses(Instrumentation inst) {
        try {
            Class<?>[] loadedClasses = inst.getAllLoadedClasses();
            System.out.println("[ChaosAgent] MemoryPressureInterceptor: Checking " + loadedClasses.length + " loaded classes for retransform");
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
                            System.out.println("[ChaosAgent] MemoryPressureInterceptor: Retransformed " + name);
                        } catch (UnmodifiableClassException ignored) {
                            System.out.println("[ChaosAgent] MemoryPressureInterceptor: UnmodifiableClassException for " + name);
                        } catch (Exception e) {
                            System.out.println("[ChaosAgent] MemoryPressureInterceptor: Error retransforming " + name + ": " + e.getMessage());
                        }
                    } else {
                        System.out.println("[ChaosAgent] MemoryPressureInterceptor: NOT modifiable: " + name);
                    }
                }
            }
            System.out.println("[ChaosAgent] MemoryPressureInterceptor: Matched=" + matched + ", Modifiable=" + modifiable + ", Retransformed=" + retransformed);
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Error retransforming classes for memory pressure: " + e.getMessage());
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
     * Advice that retains allocated objects to create controlled memory pressure.
     * Uses ThreadLocal for per-thread retention and ConcurrentHashMap for global retention.
     * All retention is capped by safety limits.
     */
    public static class MemoryPressureAdvice {
        // Retained for compatibility with callers that inspect the original advice state.
        private static final ThreadLocal<byte[]> THREAD_LOCAL_RETENTION = new ThreadLocal<>();

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Origin("#m") String methodName, @Advice.AllArguments Object[] args) {
            if (MemoryPressureAdvice.class.getClassLoader() == null) {
                BootstrapStressState.retainMemoryPressure();
            } else if (ChaosAgent.ChaosConfig.memoryPressureEnabled) {
                BootstrapStressStateAccess.configure(true, ChaosAgent.ChaosConfig.memoryPressureMb, false, 0);
                BootstrapStressState.retainMemoryPressure();
            }
        }

        /**
         * Emergency cleanup - can be called via JMX or dashboard if needed
         */
        public static void clearRetention() {
            BootstrapStressStateAccess.clearRetention();
        }

        public static long getRetainedBytes() {
            return BootstrapStressStateAccess.retainedBytes();
        }

        public static int getRetainedEntries() {
            return BootstrapStressStateAccess.retainedEntries();
        }
    }

    // Static getters for metrics exposure
    public static long getRetainedBytes() {
        return MemoryPressureAdvice.getRetainedBytes();
    }

    public static int getRetainedEntries() {
        return MemoryPressureAdvice.getRetainedEntries();
    }

    public static void clearRetention() {
        MemoryPressureAdvice.clearRetention();
    }
}
