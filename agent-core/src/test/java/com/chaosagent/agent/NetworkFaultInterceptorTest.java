package com.chaosagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkFaultInterceptorTest {

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
    }

    @AfterEach
    void resetConfigAfter() {
        resetConfig();
    }

    @Test
    void latencyDisabled_noDelay() throws Exception {
        ChaosAgent.ChaosConfig.latencyEnabled = false;
        ChaosAgent.ChaosConfig.latencyMs = 5000;

        long start = System.nanoTime();
        NetworkFaultInterceptor.ChaosAdvice.onEnter("execute");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(100); // Should not sleep
    }

    @Test
    void latencyEnabled_addsDelay() throws Exception {
        ChaosAgent.ChaosConfig.latencyEnabled = true;
        ChaosAgent.ChaosConfig.latencyMs = 100;

        long start = System.nanoTime();
        NetworkFaultInterceptor.ChaosAdvice.onEnter("execute");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(90); // Allow some tolerance
        assertThat(elapsedMs).isLessThan(500);
    }

    @Test
    void exceptionDisabled_noThrow() throws Exception {
        ChaosAgent.ChaosConfig.exceptionEnabled = false;
        ChaosAgent.ChaosConfig.exceptionType = "SocketTimeoutException";

        // Should not throw
        NetworkFaultInterceptor.ChaosAdvice.onEnter("execute");
    }

    @Test
    void exceptionEnabled_SocketTimeoutException() throws Exception {
        ChaosAgent.ChaosConfig.exceptionEnabled = true;
        ChaosAgent.ChaosConfig.exceptionType = "SocketTimeoutException";

        assertThatThrownBy(() -> NetworkFaultInterceptor.ChaosAdvice.onEnter("execute"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class)
                .hasMessageContaining("Chaos Agent: Simulated socket timeout");
    }

    @Test
    void exceptionEnabled_ConnectException() throws Exception {
        ChaosAgent.ChaosConfig.exceptionEnabled = true;
        ChaosAgent.ChaosConfig.exceptionType = "ConnectException";

        assertThatThrownBy(() -> NetworkFaultInterceptor.ChaosAdvice.onEnter("execute"))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(ConnectException.class)
                .hasMessageContaining("Chaos Agent: Simulated connection refused");
    }

    @Test
    void exceptionEnabled_Http503() throws Exception {
        ChaosAgent.ChaosConfig.exceptionEnabled = true;
        ChaosAgent.ChaosConfig.exceptionType = "Http503";

        assertThatThrownBy(() -> NetworkFaultInterceptor.ChaosAdvice.onEnter("execute"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Chaos Agent: Simulated HTTP 503");
    }

    @Test
    void exceptionEnabled_Http500() throws Exception {
        ChaosAgent.ChaosConfig.exceptionEnabled = true;
        ChaosAgent.ChaosConfig.exceptionType = "Http500";

        assertThatThrownBy(() -> NetworkFaultInterceptor.ChaosAdvice.onEnter("execute"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Chaos Agent: Simulated HTTP 500");
    }

    @Test
    void exceptionEnabled_Http504() throws Exception {
        ChaosAgent.ChaosConfig.exceptionEnabled = true;
        ChaosAgent.ChaosConfig.exceptionType = "Http504";

        assertThatThrownBy(() -> NetworkFaultInterceptor.ChaosAdvice.onEnter("execute"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Chaos Agent: Simulated HTTP 504");
    }

    @Test
    void latencyAndExceptionBothEnabled_bothApply() throws Exception {
        ChaosAgent.ChaosConfig.latencyEnabled = true;
        ChaosAgent.ChaosConfig.latencyMs = 50;
        ChaosAgent.ChaosConfig.exceptionEnabled = true;
        ChaosAgent.ChaosConfig.exceptionType = "SocketTimeoutException";

        long start = System.nanoTime();
        assertThatThrownBy(() -> NetworkFaultInterceptor.ChaosAdvice.onEnter("execute"))
                .isInstanceOf(RuntimeException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Should have slept before throwing
        assertThat(elapsedMs).isGreaterThanOrEqualTo(40);
    }

    @Test
    void exceptionTypeCaseInsensitive_notSupported() {
        // Current implementation is case-sensitive
        ChaosAgent.ChaosConfig.exceptionEnabled = true;
        ChaosAgent.ChaosConfig.exceptionType = "sockettimeoutexception";

        // Unknown type falls through to no throw (safe default)
        NetworkFaultInterceptor.ChaosAdvice.onEnter("execute"); // Should not throw
    }
}