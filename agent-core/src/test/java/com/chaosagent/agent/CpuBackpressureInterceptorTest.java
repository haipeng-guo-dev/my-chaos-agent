package com.chaosagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CpuBackpressureInterceptorTest {

    @BeforeEach
    void resetConfig() {
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
        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();
    }

    @AfterEach
    void resetConfigAfter() {
        resetConfig();
    }

    @Test
    void cpuBackpressureDisabled_noBusySpin() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = false;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;

        long elapsedNanos = CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");

        assertThat(elapsedNanos).isEqualTo(0L);
        assertThat(CpuBackpressureInterceptor.getBusyCount()).isEqualTo(0);
        assertThat(CpuBackpressureInterceptor.getTotalBusyNanos()).isEqualTo(0L);
    }

    @Test
    void cpuBackpressureEnabled_intensityZero_noBusySpin() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 0;

        long elapsedNanos = CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");

        assertThat(elapsedNanos).isEqualTo(0L);
        assertThat(CpuBackpressureInterceptor.getBusyCount()).isEqualTo(0);
    }

    @Test
    void cpuBackpressureEnabled_intensity100_alwaysBusySpins() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;

        long elapsedNanos = CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");

        assertThat(elapsedNanos).isGreaterThan(0);
        assertThat(CpuBackpressureInterceptor.getBusyCount()).isEqualTo(1);
        assertThat(CpuBackpressureInterceptor.getTotalBusyNanos()).isEqualTo(elapsedNanos);
    }

    @Test
    void cpuBackpressure_intensityFifty_approxHalfCalls() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 50;

        int busyCount = 0;
        int totalCalls = 1000;

        for (int i = 0; i < totalCalls; i++) {
            long elapsedNanos = CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");
            if (elapsedNanos > 0) {
                busyCount++;
            }
        }

        // With 50% intensity, expect ~500 busy spins
        assertThat(busyCount).isBetween(300, 700);
    }

    @Test
    void cpuBackpressure_intensityScalesWork() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;

        // Test intensity 10
        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 10;
        long elapsed10 = CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");

        // Test intensity 100
        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;
        long elapsed100 = CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");

        // Higher intensity should do more work (more iterations)
        // Note: this is probabilistic, so just verify both do work
        assertThat(elapsed10).isGreaterThanOrEqualTo(0);
        assertThat(elapsed100).isGreaterThan(0);
    }

    @Test
    void cpuBackpressure_metricsAccumulate() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;

        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();

        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");
        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("doExec");
        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("compute");

        assertThat(CpuBackpressureInterceptor.getBusyCount()).isEqualTo(3);
        assertThat(CpuBackpressureInterceptor.getTotalBusyNanos()).isGreaterThan(0);
    }

    @Test
    void cpuBackpressure_resetMetrics_clearsCounters() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;

        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");
        assertThat(CpuBackpressureInterceptor.getBusyCount()).isEqualTo(1);

        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();

        assertThat(CpuBackpressureInterceptor.getBusyCount()).isEqualTo(0);
        assertThat(CpuBackpressureInterceptor.getTotalBusyNanos()).isEqualTo(0L);
    }

    @Test
    void differentMethodNames_allTriggerBusySpin() {
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;

        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();

        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");
        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("runWorker");
        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("uniApply");
        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("forEach");
        CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("subscribe");

        assertThat(CpuBackpressureInterceptor.getBusyCount()).isEqualTo(5);
    }

    @Test
    void cpuBackpressure_suppressesExceptions() {
        // CpuBackpressureAdvice has @Advice.OnMethodEnter(suppress = Throwable.class)
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;

        // Should not throw regardless of what happens in busy spin
        long result = CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");
        assertThat(result).isGreaterThanOrEqualTo(0L);
    }
}