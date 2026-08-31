package com.chaosagent.agent;

import net.bytebuddy.asm.Advice;

/** Bootstrap-visible entry point for memory advice injected into JDK classes. */
public final class BootstrapMemoryPressureAdvice {
    private BootstrapMemoryPressureAdvice() {}

    @Advice.OnMethodEnter
    public static void enter() {
        BootstrapStressState.retainMemoryPressure();
    }
}
