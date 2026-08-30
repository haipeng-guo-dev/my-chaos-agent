package com.chaosagent.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosConfigTest {

    @Test
    void defaultValues() {
        ChaosConfig config = new ChaosConfig();

        // Phase 2: Network Fault Injection
        assertThat(config.latencyMs).isEqualTo(0);
        assertThat(config.latencyEnabled).isFalse();
        assertThat(config.exceptionEnabled).isFalse();
        assertThat(config.exceptionType).isEqualTo("SocketTimeoutException");

        // Phase 3: Deep JVM Stressors
        assertThat(config.pinningEnabled).isFalse();
        assertThat(config.pinningProbability).isEqualTo(0.1);
        assertThat(config.memoryPressureEnabled).isFalse();
        assertThat(config.memoryPressureMb).isEqualTo(100);
        assertThat(config.cpuBackpressureEnabled).isFalse();
        assertThat(config.cpuBackpressureIntensity).isEqualTo(50);
    }

    @Test
    void configFieldsAreVolatile() throws Exception {
        // Verify fields are volatile (thread-safe for concurrent access from HTTP handlers)
        var latencyMsField = ChaosConfig.class.getDeclaredField("latencyMs");
        var latencyEnabledField = ChaosConfig.class.getDeclaredField("latencyEnabled");
        var exceptionEnabledField = ChaosConfig.class.getDeclaredField("exceptionEnabled");
        var exceptionTypeField = ChaosConfig.class.getDeclaredField("exceptionType");
        var pinningEnabledField = ChaosConfig.class.getDeclaredField("pinningEnabled");
        var pinningProbabilityField = ChaosConfig.class.getDeclaredField("pinningProbability");
        var memoryPressureEnabledField = ChaosConfig.class.getDeclaredField("memoryPressureEnabled");
        var memoryPressureMbField = ChaosConfig.class.getDeclaredField("memoryPressureMb");
        var cpuBackpressureEnabledField = ChaosConfig.class.getDeclaredField("cpuBackpressureEnabled");
        var cpuBackpressureIntensityField = ChaosConfig.class.getDeclaredField("cpuBackpressureIntensity");

        assertThat(latencyMsField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(latencyEnabledField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(exceptionEnabledField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(exceptionTypeField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(pinningEnabledField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(pinningProbabilityField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(memoryPressureEnabledField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(memoryPressureMbField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(cpuBackpressureEnabledField.getModifiers()).contains(Modifier.VOLATILE);
        assertThat(cpuBackpressureIntensityField.getModifiers()).contains(Modifier.VOLATILE);
    }

    @Test
    void configCanBeMutated() {
        ChaosConfig config = new ChaosConfig();

        config.latencyMs = 5000;
        config.latencyEnabled = true;
        config.exceptionEnabled = true;
        config.exceptionType = "ConnectException";
        config.pinningEnabled = true;
        config.pinningProbability = 0.5;
        config.memoryPressureEnabled = true;
        config.memoryPressureMb = 250;
        config.cpuBackpressureEnabled = true;
        config.cpuBackpressureIntensity = 80;

        assertThat(config.latencyMs).isEqualTo(5000);
        assertThat(config.latencyEnabled).isTrue();
        assertThat(config.exceptionEnabled).isTrue();
        assertThat(config.exceptionType).isEqualTo("ConnectException");
        assertThat(config.pinningEnabled).isTrue();
        assertThat(config.pinningProbability).isEqualTo(0.5);
        assertThat(config.memoryPressureEnabled).isTrue();
        assertThat(config.memoryPressureMb).isEqualTo(250);
        assertThat(config.cpuBackpressureEnabled).isTrue();
        assertThat(config.cpuBackpressureIntensity).isEqualTo(80);
    }
}