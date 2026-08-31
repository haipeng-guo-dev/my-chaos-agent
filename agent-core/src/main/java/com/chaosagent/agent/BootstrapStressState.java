package com.chaosagent.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * State shared by advice injected into bootstrap-loaded JDK classes.
 *
 * The agent JAR is appended to the bootstrap search path, so this class has a
 * separate copy from application-loaded agent classes. Dashboard code accesses
 * that bootstrap copy through {@link BootstrapStressStateAccess}.
 */
public final class BootstrapStressState {
    private static final ConcurrentHashMap<String, byte[]> RETAINED = new ConcurrentHashMap<>();
    private static final AtomicLong RETAINED_BYTES = new AtomicLong();
    private static final AtomicLong BUSY_NANOS = new AtomicLong();
    private static final AtomicLong BUSY_COUNT = new AtomicLong();
    private static final ThreadLocal<Boolean> RETAINING_MEMORY = new ThreadLocal<>();
    private static final int MAX_RETENTION_BYTES = 500 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int[] PRIMES = buildPrimes();

    private static volatile boolean memoryPressureEnabled;
    private static volatile int memoryPressureMb = 100;
    private static volatile boolean cpuBackpressureEnabled;
    private static volatile int cpuBackpressureIntensity = 50;

    private BootstrapStressState() {}

    public static void configure(boolean memoryEnabled, int memoryMb, boolean cpuEnabled, int cpuIntensity) {
        memoryPressureEnabled = memoryEnabled;
        memoryPressureMb = memoryMb;
        cpuBackpressureEnabled = cpuEnabled;
        cpuBackpressureIntensity = cpuIntensity;
    }

    public static void retainMemoryPressure() {
        // Bookkeeping can allocate strings and collections that are themselves targets.
        if (Boolean.TRUE.equals(RETAINING_MEMORY.get())) return;
        RETAINING_MEMORY.set(Boolean.TRUE);
        try {
        if (!memoryPressureEnabled || memoryPressureMb <= 0) return;
        long currentTotal = RETAINED_BYTES.get();
        if (currentTotal >= MAX_RETENTION_BYTES || RETAINED.size() >= MAX_ENTRIES) return;
        if (ThreadLocalRandom.current().nextInt(100) >= 10) return;

        long targetBytes = (long) memoryPressureMb * 1024 * 1024;
        long remaining = targetBytes - currentTotal;
        if (remaining <= 0) return;

        int retainSize = 1024 + ThreadLocalRandom.current().nextInt(100 * 1024);
        retainSize = (int) Math.min(retainSize, remaining);
        byte[] data = new byte[retainSize];
        ThreadLocalRandom.current().nextBytes(data);
        if (RETAINED.putIfAbsent("chaos-mem-" + System.nanoTime() + '-' + ThreadLocalRandom.current().nextLong(), data) == null) {
            RETAINED_BYTES.addAndGet(retainSize);
        }
        } finally {
            RETAINING_MEMORY.remove();
        }
    }

    public static long applyCpuBackpressure() {
        if (!cpuBackpressureEnabled || cpuBackpressureIntensity <= 0) return 0L;
        if (ThreadLocalRandom.current().nextInt(100) >= cpuBackpressureIntensity) return 0L;

        long start = System.nanoTime();
        long result = 0;
        int iterations = 1_000 + cpuBackpressureIntensity * 1_000;
        for (int i = 0; i < iterations; i++) {
            result ^= PRIMES[i % PRIMES.length] * (long) (i + 1);
            result = (result * 31) ^ (result >>> 16);
        }
        if (result == Long.MIN_VALUE) System.out.println("chaos-cpu-result: " + result);
        long elapsed = System.nanoTime() - start;
        BUSY_NANOS.addAndGet(elapsed);
        BUSY_COUNT.incrementAndGet();
        return elapsed;
    }

    public static long getRetainedBytes() { return RETAINED_BYTES.get(); }
    public static int getRetainedEntries() { return RETAINED.size(); }
    public static long getBusyNanos() { return BUSY_NANOS.get(); }
    public static long getBusyCount() { return BUSY_COUNT.get(); }
    public static void clearRetention() { RETAINED.clear(); RETAINED_BYTES.set(0); }
    public static void resetCpuMetrics() { BUSY_NANOS.set(0); BUSY_COUNT.set(0); }

    private static int[] buildPrimes() {
        int[] primes = new int[10_000];
        int count = 0;
        for (int candidate = 2; count < primes.length; candidate++) {
            boolean prime = true;
            for (int divisor = 2; divisor * divisor <= candidate; divisor++) {
                if (candidate % divisor == 0) { prime = false; break; }
            }
            if (prime) primes[count++] = candidate;
        }
        return primes;
    }
}
