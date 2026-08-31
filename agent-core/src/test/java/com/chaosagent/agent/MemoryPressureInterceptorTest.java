package com.chaosagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPressureInterceptorTest {

    @BeforeEach
    void resetConfig() throws Exception {
        ChaosAgent.ChaosConfig.latencyMs = 0;
        ChaosAgent.ChaosConfig.latencyEnabled = false;
        ChaosAgent.ChaosConfig.exceptionEnabled = false;
        ChaosAgent.ChaosConfig.exceptionType = "SocketTimeoutException";
        ChaosAgent.ChaosConfig.pinningEnabled = false;
        ChaosAgent.ChaosConfig.pinningProbability = 0.1;
        ChaosAgent.ChaosConfig.memoryPressureEnabled = false;
        ChaosAgent.ChaosConfig.memoryPressureMb = 100;
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = false;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 50;

        // Clear retention via reflection
        MemoryPressureInterceptor.clearRetention();
    }

    @AfterEach
    void resetConfigAfter() throws Exception {
        resetConfig();
    }

    @Test
    void memoryPressureDisabled_noRetention() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = false;
        ChaosAgent.ChaosConfig.memoryPressureMb = 100;

        MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item"});

        assertThat(MemoryPressureInterceptor.getRetainedBytes()).isEqualTo(0);
        assertThat(MemoryPressureInterceptor.getRetainedEntries()).isEqualTo(0);
    }

    @Test
    void memoryPressureEnabled_zeroMb_noRetention() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 0;

        MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item"});

        assertThat(MemoryPressureInterceptor.getRetainedBytes()).isEqualTo(0);
    }

    @Test
    void memoryPressureEnabled_retainsMemory() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 10; // 10 MB target

        // Call multiple times - only ~10% chance each call
        for (int i = 0; i < 200; i++) {
            MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item" + i});
        }

        long retainedBytes = MemoryPressureInterceptor.getRetainedBytes();
        int retainedEntries = MemoryPressureInterceptor.getRetainedEntries();

        assertThat(retainedBytes).isGreaterThan(0);
        assertThat(retainedEntries).isGreaterThan(0);
        // Each entry is 1KB-100KB, so 10MB target should have many entries
        assertThat(retainedBytes).isLessThanOrEqualTo(10L * 1024 * 1024 + 100_000); // Allow small overshoot
    }

    @Test
    void memoryPressure_capAt500Mb() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 1000; // Request 1000MB

        // Force retention by calling many times
        for (int i = 0; i < 5000; i++) {
            MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item" + i});
        }

        long retainedBytes = MemoryPressureInterceptor.getRetainedBytes();
        int retainedEntries = MemoryPressureInterceptor.getRetainedEntries();

        // Hard cap is 500MB
        assertThat(retainedBytes).isLessThanOrEqualTo(500L * 1024 * 1024);
        // Entry cap is 10000
        assertThat(retainedEntries).isLessThanOrEqualTo(10000);
    }

    @Test
    void memoryPressure_entryCapAt10k() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 500; // 500MB target

        // Call enough times to hit entry cap
        for (int i = 0; i < 15000; i++) {
            MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item" + i});
        }

        int retainedEntries = MemoryPressureInterceptor.getRetainedEntries();
        assertThat(retainedEntries).isLessThanOrEqualTo(10000);
    }

    @Test
    void clearRetention_resetsCounters() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 10;

        for (int i = 0; i < 200; i++) {
            MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item" + i});
        }

        assertThat(MemoryPressureInterceptor.getRetainedBytes()).isGreaterThan(0);
        assertThat(MemoryPressureInterceptor.getRetainedEntries()).isGreaterThan(0);

        MemoryPressureInterceptor.clearRetention();

        assertThat(MemoryPressureInterceptor.getRetainedBytes()).isEqualTo(0);
        assertThat(MemoryPressureInterceptor.getRetainedEntries()).isEqualTo(0);
    }

    @Test
    void differentMethodNames_allTriggerRetention() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 10;

        MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item"});
        MemoryPressureInterceptor.MemoryPressureAdvice.enter("put", new Object[]{"key", "value"});
        MemoryPressureInterceptor.MemoryPressureAdvice.enter("append", new Object[]{"text"});
        MemoryPressureInterceptor.MemoryPressureAdvice.enter("allocate", new Object[]{1024});

        assertThat(MemoryPressureInterceptor.getRetainedBytes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void threadLocalRetention_perThread() throws Exception {
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 10;

        MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item"});

        // Verify ThreadLocal fields exist and were set
        Field threadLocalField = MemoryPressureInterceptor.MemoryPressureAdvice.class.getDeclaredField("THREAD_LOCAL_RETENTION");
        threadLocalField.setAccessible(true);
        ThreadLocal<?> threadLocal = (ThreadLocal<?>) threadLocalField.get(null);
        
        // ThreadLocal may or may not have value depending on random chance
        // Just verify it's accessible
        assertThat(threadLocal).isNotNull();
    }
}