package com.chaosagent.agent;

import java.lang.reflect.Method;

/** Accesses the bootstrap copy of {@link BootstrapStressState} when the agent is active. */
public final class BootstrapStressStateAccess {
    private static final String STATE_CLASS = "com.chaosagent.agent.BootstrapStressState";

    private BootstrapStressStateAccess() {}

    public static void configure(boolean memoryEnabled, int memoryMb, boolean cpuEnabled, int cpuIntensity) {
        invokeVoid("configure", new Class<?>[]{boolean.class, int.class, boolean.class, int.class}, memoryEnabled, memoryMb, cpuEnabled, cpuIntensity);
    }

    public static long retainedBytes() { return ((Number) invoke("getRetainedBytes")).longValue(); }
    public static int retainedEntries() { return ((Number) invoke("getRetainedEntries")).intValue(); }
    public static long busyNanos() { return ((Number) invoke("getBusyNanos")).longValue(); }
    public static long busyCount() { return ((Number) invoke("getBusyCount")).longValue(); }
    public static void clearRetention() { invokeVoid("clearRetention", new Class<?>[0]); }
    public static void resetCpuMetrics() { invokeVoid("resetCpuMetrics", new Class<?>[0]); }

    private static Object invoke(String methodName) {
        try {
            Class<?> state = Class.forName(STATE_CLASS, true, null);
            return state.getMethod(methodName).invoke(null);
        } catch (ClassNotFoundException ignored) {
            return localValue(methodName);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access bootstrap stress state", e);
        }
    }

    private static void invokeVoid(String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> state = Class.forName(STATE_CLASS, true, null);
            Method method = state.getMethod(methodName, parameterTypes);
            method.invoke(null, arguments);
        } catch (ClassNotFoundException ignored) {
            localVoid(methodName, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access bootstrap stress state", e);
        }
    }

    private static Object localValue(String methodName) {
        return switch (methodName) {
            case "getRetainedBytes" -> BootstrapStressState.getRetainedBytes();
            case "getRetainedEntries" -> BootstrapStressState.getRetainedEntries();
            case "getBusyNanos" -> BootstrapStressState.getBusyNanos();
            case "getBusyCount" -> BootstrapStressState.getBusyCount();
            default -> throw new IllegalArgumentException("Unknown stress-state method: " + methodName);
        };
    }

    private static void localVoid(String methodName, Object... arguments) {
        switch (methodName) {
            case "configure" -> BootstrapStressState.configure((boolean) arguments[0], (int) arguments[1], (boolean) arguments[2], (int) arguments[3]);
            case "clearRetention" -> BootstrapStressState.clearRetention();
            case "resetCpuMetrics" -> BootstrapStressState.resetCpuMetrics();
            default -> throw new IllegalArgumentException("Unknown stress-state method: " + methodName);
        }
    }
}
