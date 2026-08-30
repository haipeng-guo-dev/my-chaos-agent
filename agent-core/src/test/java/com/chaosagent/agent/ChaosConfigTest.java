package com.chaosagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosConfigTest {

    @BeforeEach
    void resetConfig() {
        // Reset to defaults before each test
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
    }

    @AfterEach
    void resetConfigAfter() {
        resetConfig();
    }

    @Test
    void defaultValues() {
        ChaosAgent.ChaosConfig config = new ChaosAgent.ChaosConfig();

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
        var latencyMsField = ChaosAgent.ChaosConfig.class.getDeclaredField("latencyMs");
        var latencyEnabledField = ChaosAgent.ChaosConfig.class.getDeclaredField("latencyEnabled");
        var exceptionEnabledField = ChaosAgent.ChaosConfig.class.getDeclaredField("exceptionEnabled");
        var exceptionTypeField = ChaosAgent.ChaosConfig.class.getDeclaredField("exceptionType");
        var pinningEnabledField = ChaosAgent.ChaosConfig.class.getDeclaredField("pinningEnabled");
        var pinningProbabilityField = ChaosAgent.ChaosConfig.class.getDeclaredField("pinningProbability");
        var memoryPressureEnabledField = ChaosAgent.ChaosConfig.class.getDeclaredField("memoryPressureEnabled");
        var memoryPressureMbField = ChaosAgent.ChaosConfig.class.getDeclaredField("memoryPressureMb");
        var cpuBackpressureEnabledField = ChaosAgent.ChaosConfig.class.getDeclaredField("cpuBackpressureEnabled");
        var cpuBackpressureIntensityField = ChaosAgent.ChaosConfig.class.getDeclaredField("cpuBackpressureIntensity");

        assertThat(latencyMsField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(latencyEnabledField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(exceptionEnabledField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(exceptionTypeField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(pinningEnabledField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(pinningProbabilityField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(memoryPressureEnabledField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(memoryPressureMbField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(cpuBackpressureEnabledField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
        assertThat(cpuBackpressureIntensityField.getModifiers() & Modifier.VOLATILE).isNotEqualTo(0);
    }

    @Test
    void configCanBeMutated() {
        ChaosAgent.ChaosConfig config = new ChaosAgent.ChaosConfig();

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