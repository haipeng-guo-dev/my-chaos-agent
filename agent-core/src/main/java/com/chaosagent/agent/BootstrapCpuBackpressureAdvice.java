package com.chaosagent.agent;

import net.bytebuddy.asm.Advice;

/** Bootstrap-visible entry point for CPU advice injected into JDK classes. */
public final class BootstrapCpuBackpressureAdvice {
    private BootstrapCpuBackpressureAdvice() {}

    @Advice.OnMethodEnter
    public static void enter() {
        BootstrapStressState.applyCpuBackpressure();
    }
}
