package com.chaosagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierPinningInterceptorTest {

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
    void pinningDisabled_noAction() {
        ChaosAgent.ChaosConfig.pinningEnabled = false;
        ChaosAgent.ChaosConfig.pinningProbability = 1.0;

        long start = System.nanoTime();
        CarrierPinningInterceptor.PinningAdvice.enter("execute");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Should return immediately without synchronized block work
        assertThat(elapsedMs).isLessThan(50);
    }

    @Test
    void pinningEnabled_probabilityZero_noPin() {
        ChaosAgent.ChaosConfig.pinningEnabled = true;
        ChaosAgent.ChaosConfig.pinningProbability = 0.0;

        long start = System.nanoTime();
        CarrierPinningInterceptor.PinningAdvice.enter("execute");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(50);
    }

    @Test
    void pinningEnabled_probabilityOne_alwaysPins() {
        ChaosAgent.ChaosConfig.pinningEnabled = true;
        ChaosAgent.ChaosConfig.pinningProbability = 1.0;

        long start = System.nanoTime();
        CarrierPinningInterceptor.PinningAdvice.enter("execute");
        long elapsedNanos = System.nanoTime() - start;

        // Should execute synchronized block with busy spin
        // Should take at least a few microseconds
        assertThat(elapsedNanos).isGreaterThan(1000);
    }

    @Test
    void pinningProbability_fractional_deterministicOverManyCalls() {
        ChaosAgent.ChaosConfig.pinningEnabled = true;
        ChaosAgent.ChaosConfig.pinningProbability = 0.5;

        int pinCount = 0;
        int totalCalls = 10000;

        for (int i = 0; i < totalCalls; i++) {
            long start = System.nanoTime();
            CarrierPinningInterceptor.PinningAdvice.enter("execute");
            long elapsedNanos = System.nanoTime() - start;
            
            if (elapsedNanos > 1000) {
                pinCount++;
            }
        }

        // With 50% probability over 10k calls, expect ~5000 pins
        // Allow wide margin due to randomness and timing variance
        assertThat(pinCount).isBetween(3000, 7000);
    }

    @Test
    void pinningAdvice_suppressesExceptions() {
        // PinningAdvice has @Advice.OnMethodEnter(suppress = Throwable.class)
        // Even if synchronized block throws, it should be suppressed
        ChaosAgent.ChaosConfig.pinningEnabled = true;
        ChaosAgent.ChaosConfig.pinningProbability = 1.0;

        // Should not throw regardless of what happens in synchronized block
        CarrierPinningInterceptor.PinningAdvice.enter("execute");
        CarrierPinningInterceptor.PinningAdvice.enter("run");
        CarrierPinningInterceptor.PinningAdvice.enter("submit");
    }

    @Test
    void differentMethodNames_allWork() {
        ChaosAgent.ChaosConfig.pinningEnabled = true;
        ChaosAgent.ChaosConfig.pinningProbability = 1.0;

        // Different method names should all trigger pinning
        CarrierPinningInterceptor.PinningAdvice.enter("start");
        CarrierPinningInterceptor.PinningAdvice.enter("run");
        CarrierPinningInterceptor.PinningAdvice.enter("execute");
        CarrierPinningInterceptor.PinningAdvice.enter("submit");
        CarrierPinningInterceptor.PinningAdvice.enter("invoke");
        CarrierPinningInterceptor.PinningAdvice.enter("postProcessAfterInitialization");
    }
}