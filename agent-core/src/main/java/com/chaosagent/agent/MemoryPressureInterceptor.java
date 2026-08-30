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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

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

    // Managed memory retention structures with safety caps
    private static final ConcurrentHashMap<String, byte[]> MEMORY_RETENTION_MAP = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_RETAINED_BYTES = new AtomicLong(0);
    private static final int MAX_RETENTION_MB = 500; // Hard safety cap: 500MB max
    private static final int MAX_ENTRIES = 10000; // Max entries in retention map

    public static void install(Instrumentation inst) {
        ElementMatcher.Junction<TypeDescription> typeMatcher = buildTypeMatcher();

        AgentBuilder.Transformer transformer = (builder, typeDescription, classLoader, module, protectionDomain) -> {
            String className = typeDescription.getName();
            TargetSpec spec = findSpec(className);
            if (spec == null) return builder;

            ElementMatcher<net.bytebuddy.description.method.MethodDescription> methodMatcher = 
                ElementMatchers.namedOneOf(spec.methodNames);

            System.out.println("[ChaosAgent] Transforming for memory pressure: " + spec.displayName + " (" + className + ")");
            return builder.visit(Advice.to(MemoryPressureAdvice.class).on(methodMatcher));
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
            System.err.println("[ChaosAgent] Error retransforming classes for memory pressure: " + e.getMessage());
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
        // Thread-local retention for per-thread memory pressure
        private static final ThreadLocal<byte[]> THREAD_LOCAL_RETENTION = ThreadLocal.withInitial(() -> null);
        private static final ThreadLocal<Long> THREAD_LOCAL_SIZE = ThreadLocal.withInitial(() -> 0L);

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Origin("#m") String methodName, @Advice.AllArguments Object[] args) {
            if (!ChaosAgent.ChaosConfig.memoryPressureEnabled) return;
            
            int targetMb = ChaosAgent.ChaosConfig.memoryPressureMb;
            if (targetMb <= 0) return;

            long targetBytes = (long) targetMb * 1024 * 1024;
            long currentTotal = TOTAL_RETAINED_BYTES.get();
            
            // Safety cap check
            if (currentTotal >= MAX_RETENTION_MB * 1024L * 1024L) {
                return; // Hard cap reached
            }
            if (MEMORY_RETENTION_MAP.size() >= MAX_ENTRIES) {
                return; // Entry cap reached
            }

            // Only retain on a subset of calls to avoid overwhelming
            if (ThreadLocalRandom.current().nextInt(100) > 10) { // 10% chance
                return;
            }

            // Calculate size to retain (1KB - 100KB per allocation site)
            int retainSize = 1024 + ThreadLocalRandom.current().nextInt(100 * 1024);
            
            // Don't exceed target
            long remaining = targetBytes - currentTotal;
            if (remaining <= 0) return;
            if (retainSize > remaining) {
                retainSize = (int) Math.min(retainSize, remaining);
            }

            try {
                // Allocate and retain in global map
                byte[] data = new byte[retainSize];
                // Fill with pseudo-random data to prevent deduplication/compression
                ThreadLocalRandom.current().nextBytes(data);
                
                String key = "chaos-mem-" + System.nanoTime() + "-" + ThreadLocalRandom.current().nextLong();
                byte[] previous = MEMORY_RETENTION_MAP.put(key, data);
                
                if (previous == null) {
                    TOTAL_RETAINED_BYTES.addAndGet(retainSize);
                } else {
                    TOTAL_RETAINED_BYTES.addAndGet(retainSize - previous.length);
                }

                // Also retain in thread-local for additional pressure
                byte[] threadLocalData = THREAD_LOCAL_RETENTION.get();
                if (threadLocalData == null || threadLocalData.length < retainSize) {
                    THREAD_LOCAL_RETENTION.set(data);
                    THREAD_LOCAL_SIZE.set((long) retainSize);
                }
            } catch (OutOfMemoryError e) {
                // Safety: if we hit OOM, clear our retention and re-throw
                clearRetention();
                throw e;
            } catch (Throwable ignored) {
                // Ignore any other errors
            }
        }

        /**
         * Emergency cleanup - can be called via JMX or dashboard if needed
         */
        public static void clearRetention() {
            MEMORY_RETENTION_MAP.clear();
            TOTAL_RETAINED_BYTES.set(0);
            // ThreadLocals will be cleaned up when threads die
        }

        public static long getRetainedBytes() {
            return TOTAL_RETAINED_BYTES.get();
        }

        public static int getRetainedEntries() {
            return MEMORY_RETENTION_MAP.size();
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