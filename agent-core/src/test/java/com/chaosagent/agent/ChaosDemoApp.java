package com.chaosagent.agent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Simple demo app that exercises instrumented code paths:
 * - ForkJoinPool (carrier threads for virtual threads)
 * - CompletableFuture async chains
 * - Stream parallel operations
 */
public class ChaosDemoApp {

    public static void main(String[] args) throws Exception {
        String port = args.length == 1 ? args[0] : "8090";
        System.out.println("=== Chaos Agent Demo App ===");
        System.out.println("PID: " + ProcessHandle.current().pid());
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println();

        // 1. ForkJoinPool tasks (instrumented via ForkJoinTask.exec/doExec/compute)
        System.out.println("1. Running ForkJoinPool tasks...");
        ForkJoinPool.commonPool().submit(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            return "forkjoin-done";
        }).join();

        // 2. CompletableFuture chain (instrumented via uniApply/uniCompose/etc)
        System.out.println("2. Running CompletableFuture chain...");
        CompletableFuture.supplyAsync(() -> "step1")
            .thenApply(s -> s + "->step2")
            .thenCompose(s -> CompletableFuture.supplyAsync(() -> s + "->step3"))
            .thenAccept(System.out::println)
            .join();

        // 3. Parallel stream (instrumented via ReferencePipeline.forEach/collect)
        System.out.println("3. Running parallel stream...");
        IntStream.range(0, 1000).parallel().forEach(i -> {
            // Busy work
            long sum = 0;
            for (int j = 0; j < 1000; j++) sum += j;
        });

        // 4. Virtual thread pinning targets (Thread.start, Executor.execute)
        System.out.println("4. Starting virtual threads...");
        if (Thread.class.getMethod("ofVirtual").getReturnType().equals(Thread.class)) {
            Thread.startVirtualThread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                System.out.println("Virtual thread completed");
            }).join();
        }

        // 5. Memory pressure targets (Collection.add, Map.put, etc)
        System.out.println("5. Exercising collections...");
        var list = new java.util.ArrayList<String>();
        var map = new java.util.HashMap<String, String>();
        for (int i = 0; i < 1_000; i++) {
            list.add("item-" + i);
            map.put("key-" + i, "value-" + i);
        }

        System.out.println();
        System.out.println("=== Demo complete ===");
        System.out.println("Check dashboard at http://localhost:" + port);
        System.out.println("Mem Retained & CPU Busy-Spin should now show non-zero values");
        
        // Keep JVM alive for dashboard inspection
        Thread.sleep(30_000);
    }
}
