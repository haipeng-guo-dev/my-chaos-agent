package com.chaosagent.agent;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class ChaosAgent {

    private static final int DEFAULT_PORT = 8090;
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicReference<HttpServer> serverRef = new AtomicReference<>();
    private static final java.util.concurrent.ScheduledExecutorService metricsBroadcaster = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chaos-agent-metrics-broadcaster");
        t.setDaemon(true);
        return t;
    });

    // SSE clients for real-time metrics streaming
    private static final List<com.sun.net.httpserver.HttpExchange> sseClients = new CopyOnWriteArrayList<>();
    private static final AtomicLong metricsSequence = new AtomicLong(0);

    public static void premain(String agentArgs, Instrumentation inst) {
        start(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        start(agentArgs, inst);
    }

    private static void start(String agentArgs, Instrumentation inst) {
        if (!started.compareAndSet(false, true)) {
            System.out.println("[ChaosAgent] Already started, skipping");
            return;
        }

        int port = parsePort(agentArgs);
        System.out.println("[ChaosAgent] Starting on port " + port);
        System.out.println("[ChaosAgent] Java version: " + System.getProperty("java.version"));
        System.out.println("[ChaosAgent] PID: " + ProcessHandle.current().pid());

        // Install network fault interceptors
        try {
            NetworkFaultInterceptor.install(inst);
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Failed to install network interceptors: " + e.getMessage());
            e.printStackTrace();
        }

        // Install Phase 3: Deep JVM Stressors
        try {
            CarrierPinningInterceptor.install(inst);
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Failed to install carrier pinning interceptor: " + e.getMessage());
            e.printStackTrace();
        }

        try {
            MemoryPressureInterceptor.install(inst);
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Failed to install memory pressure interceptor: " + e.getMessage());
            e.printStackTrace();
        }

        try {
            CpuBackpressureInterceptor.install(inst);
        } catch (Exception e) {
            System.err.println("[ChaosAgent] Failed to install CPU backpressure interceptor: " + e.getMessage());
            e.printStackTrace();
        }

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
                private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
                private int count = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = defaultFactory.newThread(r);
                    t.setName("chaos-agent-http-" + (++count));
                    t.setDaemon(true);
                    return t;
                }
            }));

            server.createContext("/", exchange -> serveDashboard(exchange));
            server.createContext("/api/status", exchange -> serveStatus(exchange));
            server.createContext("/api/config", exchange -> serveConfig(exchange));
            server.createContext("/api/metrics/stream", exchange -> serveMetricsStream(exchange));
            server.createContext("/api/profile", exchange -> serveProfile(exchange));
            server.createContext("/static", exchange -> serveStaticResource(exchange));

            server.start();
            serverRef.set(server);
            // Start periodic metrics broadcast for SSE clients
            metricsBroadcaster.scheduleAtFixedRate(ChaosAgent::broadcastMetrics, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("[ChaosAgent] Dashboard available at http://localhost:" + port);
            System.out.println("[ChaosAgent] Agent started successfully");
        } catch (IOException e) {
            System.err.println("[ChaosAgent] Failed to start HTTP server: " + e.getMessage());
            e.printStackTrace();
            started.set(false);
        }
    }

    private static int parsePort(String agentArgs) {
        if (agentArgs == null || agentArgs.isBlank()) return DEFAULT_PORT;
        for (String part : agentArgs.split(",")) {
            if (part.startsWith("port=")) {
                try {
                    return Integer.parseInt(part.substring(5));
                } catch (NumberFormatException ignored) {}
            }
        }
        return DEFAULT_PORT;
    }

    private static void serveDashboard(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String html = loadResource("index.html");
        if (html == null) {
            html = "<html><body><h1>Chaos Agent Dashboard</h1><p>index.html not found in resources</p></body></html>";
        }
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, html.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes());
        }
    }

    private static void serveStatus(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long usedMem = totalMem - freeMem;
        long maxMem = rt.maxMemory();

        String json = String.format("""
            {
              "timestamp": "%s",
              "jvm": {
                "version": "%s",
                "vendor": "%s",
                "pid": %d
              },
              "memory": {
                "used": %d,
                "free": %d,
                "total": %d,
                "max": %d,
                "usedPercent": %.1f
              },
              "threads": {
                "count": %d,
                "peakCount": %d
              },
              "agent": {
                "started": true,
                "port": %d
              }
            }
            """,
            Instant.now().toString(),
            System.getProperty("java.version"),
            System.getProperty("java.vendor"),
            ProcessHandle.current().pid(),
            usedMem, freeMem, totalMem, maxMem,
            maxMem > 0 ? (usedMem * 100.0 / maxMem) : 0,
            Thread.activeCount(),
            getPeakThreadCount(),
            getPort()
        );

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, json.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes());
        }
    }

    private static void serveConfig(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if ("GET".equals(method)) {
            String json = String.format("""
                {
                  "latencyMs": %d,
                  "latencyEnabled": %b,
                  "exceptionEnabled": %b,
                  "exceptionType": "%s",
                  "pinningEnabled": %b,
                  "pinningProbability": %.2f,
                  "memoryPressureEnabled": %b,
                  "memoryPressureMb": %d,
                  "cpuBackpressureEnabled": %b,
                  "cpuBackpressureIntensity": %d
                }
                """,
                ChaosConfig.latencyMs, ChaosConfig.latencyEnabled, ChaosConfig.exceptionEnabled, ChaosConfig.exceptionType,
                ChaosConfig.pinningEnabled, ChaosConfig.pinningProbability,
                ChaosConfig.memoryPressureEnabled, ChaosConfig.memoryPressureMb,
                ChaosConfig.cpuBackpressureEnabled, ChaosConfig.cpuBackpressureIntensity
            );
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        } else if ("POST".equals(method)) {
            String body = new String(exchange.getRequestBody().readAllBytes());
            ChaosConfig.latencyMs = extractInt(body, "latencyMs", ChaosConfig.latencyMs);
            ChaosConfig.latencyEnabled = extractBoolean(body, "latencyEnabled", ChaosConfig.latencyEnabled);
            ChaosConfig.exceptionEnabled = extractBoolean(body, "exceptionEnabled", ChaosConfig.exceptionEnabled);
            ChaosConfig.exceptionType = extractString(body, "exceptionType", ChaosConfig.exceptionType);
            ChaosConfig.pinningEnabled = extractBoolean(body, "pinningEnabled", ChaosConfig.pinningEnabled);
            ChaosConfig.pinningProbability = extractDouble(body, "pinningProbability", ChaosConfig.pinningProbability);
            ChaosConfig.memoryPressureEnabled = extractBoolean(body, "memoryPressureEnabled", ChaosConfig.memoryPressureEnabled);
            ChaosConfig.memoryPressureMb = extractInt(body, "memoryPressureMb", ChaosConfig.memoryPressureMb);
            ChaosConfig.cpuBackpressureEnabled = extractBoolean(body, "cpuBackpressureEnabled", ChaosConfig.cpuBackpressureEnabled);
            ChaosConfig.cpuBackpressureIntensity = extractInt(body, "cpuBackpressureIntensity", ChaosConfig.cpuBackpressureIntensity);

            String json = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        } else {
            exchange.sendResponseHeaders(405, -1);
        }
    }

    private static void serveMetricsStream(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        sseClients.add(exchange);

        // Send initial event
        String initialEvent = buildMetricsEvent();
        os.write(("data: " + initialEvent + "\n\n").getBytes());
        os.flush();

        // Keep connection alive - the client will close when done
        // We'll clean up when writes fail
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ignored) {
        } finally {
            sseClients.remove(exchange);
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    private static String buildMetricsEvent() {
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long usedMem = totalMem - freeMem;
        long maxMem = rt.maxMemory();

        // Get Phase 3 metrics
        long memRetained = 0;
        long memEntries = 0;
        long cpuBusyNanos = 0;
        long cpuBusyCount = 0;

        try {
            memRetained = MemoryPressureInterceptor.getRetainedBytes();
            memEntries = MemoryPressureInterceptor.getRetainedEntries();
        } catch (Exception ignored) {}
        try {
            cpuBusyNanos = CpuBackpressureInterceptor.getTotalBusyNanos();
            cpuBusyCount = CpuBackpressureInterceptor.getBusyCount();
        } catch (Exception ignored) {}

        long seq = metricsSequence.incrementAndGet();

        return String.format(
            "{\"seq\":%d,\"timestamp\":\"%s\",\"memory\":{\"used\":%d,\"max\":%d,\"usedPercent\":%.1f},\"threads\":%d,\"phase3\":{\"memRetainedMb\":%d,\"memEntries\":%d,\"cpuBusyMs\":%d,\"cpuBusyCount\":%d}}",
            seq,
            Instant.now().toString(),
            usedMem, maxMem,
            maxMem > 0 ? (usedMem * 100.0 / maxMem) : 0,
            Thread.activeCount(),
            memRetained / (1024 * 1024),
            memEntries,
            cpuBusyNanos / 1_000_000,
            cpuBusyCount
        );
    }

    public static void broadcastMetrics() {
        String event = buildMetricsEvent();
        String data = "data: " + event + "\n\n";
        byte[] bytes = data.getBytes();

        for (com.sun.net.httpserver.HttpExchange client : sseClients) {
            try {
                client.getResponseBody().write(bytes);
                client.getResponseBody().flush();
            } catch (IOException e) {
                sseClients.remove(client);
            }
        }
    }

    private static void serveProfile(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if ("GET".equals(method)) {
            // Export current config as profile
            String json = String.format("""
                {
                  "version": 1,
                  "timestamp": "%s",
                  "name": "chaos-profile-%s",
                  "config": {
                    "latencyMs": %d,
                    "latencyEnabled": %b,
                    "exceptionEnabled": %b,
                    "exceptionType": "%s",
                    "pinningEnabled": %b,
                    "pinningProbability": %.2f,
                    "memoryPressureEnabled": %b,
                    "memoryPressureMb": %d,
                    "cpuBackpressureEnabled": %b,
                    "cpuBackpressureIntensity": %d
                  }
                }
                """,
                Instant.now().toString(),
                Instant.now().toEpochMilli(),
                ChaosConfig.latencyMs, ChaosConfig.latencyEnabled, ChaosConfig.exceptionEnabled, ChaosConfig.exceptionType,
                ChaosConfig.pinningEnabled, ChaosConfig.pinningProbability,
                ChaosConfig.memoryPressureEnabled, ChaosConfig.memoryPressureMb,
                ChaosConfig.cpuBackpressureEnabled, ChaosConfig.cpuBackpressureIntensity
            );
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=chaos-profile.json");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        } else if ("POST".equals(method)) {
            // Import profile
            String body = new String(exchange.getRequestBody().readAllBytes());

            // Extract config from profile
            int latencyMs = extractInt(body, "latencyMs", ChaosConfig.latencyMs);
            boolean latencyEnabled = extractBoolean(body, "latencyEnabled", ChaosConfig.latencyEnabled);
            boolean exceptionEnabled = extractBoolean(body, "exceptionEnabled", ChaosConfig.exceptionEnabled);
            String exceptionType = extractString(body, "exceptionType", ChaosConfig.exceptionType);
            boolean pinningEnabled = extractBoolean(body, "pinningEnabled", ChaosConfig.pinningEnabled);
            double pinningProbability = extractDouble(body, "pinningProbability", ChaosConfig.pinningProbability);
            boolean memoryPressureEnabled = extractBoolean(body, "memoryPressureEnabled", ChaosConfig.memoryPressureEnabled);
            int memoryPressureMb = extractInt(body, "memoryPressureMb", ChaosConfig.memoryPressureMb);
            boolean cpuBackpressureEnabled = extractBoolean(body, "cpuBackpressureEnabled", ChaosConfig.cpuBackpressureEnabled);
            int cpuBackpressureIntensity = extractInt(body, "cpuBackpressureIntensity", ChaosConfig.cpuBackpressureIntensity);

            // Apply config
            ChaosConfig.latencyMs = latencyMs;
            ChaosConfig.latencyEnabled = latencyEnabled;
            ChaosConfig.exceptionEnabled = exceptionEnabled;
            ChaosConfig.exceptionType = exceptionType;
            ChaosConfig.pinningEnabled = pinningEnabled;
            ChaosConfig.pinningProbability = pinningProbability;
            ChaosConfig.memoryPressureEnabled = memoryPressureEnabled;
            ChaosConfig.memoryPressureMb = memoryPressureMb;
            ChaosConfig.cpuBackpressureEnabled = cpuBackpressureEnabled;
            ChaosConfig.cpuBackpressureIntensity = cpuBackpressureIntensity;

            String json = "{\"status\":\"ok\",\"message\":\"Profile imported successfully\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, json.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
        } else {
            exchange.sendResponseHeaders(405, -1);
        }
    }

    private static double extractDouble(String json, String key, double defaultValue) {
        String pattern = "\\\"" + key + "\\\"\\s*:\\s*([\\d.]+)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : defaultValue;
    }

    private static int extractInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
    }

    private static boolean extractBoolean(String json, String key, boolean defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*(true|false)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : defaultValue;
    }

    private static String extractString(String json, String key, String defaultValue) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : defaultValue;
    }

    private static void serveStaticResource(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        // /static/... -> remove /static prefix
        String resourcePath = path.substring("/static".length());
        if (resourcePath.isEmpty() || resourcePath.equals("/")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        if (resourcePath.startsWith("/")) resourcePath = resourcePath.substring(1);

        try (var is = ChaosAgent.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] content = is.readAllBytes();
            String contentType = getContentType(resourcePath);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        } catch (Exception e) {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    private static String getContentType(String path) {
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }

    private static String loadResource(String name) {
        try (var is = ChaosAgent.class.getClassLoader().getResourceAsStream(name)) {
            if (is != null) return new String(is.readAllBytes());
        } catch (Exception ignored) {}
        // Fallback: read from the agent JAR directly
        try {
            String agentJar = System.getProperty("java.class.path").split(java.io.File.pathSeparator)[0];
            try (var fs = java.nio.file.FileSystems.newFileSystem(java.nio.file.Paths.get(agentJar), java.util.Collections.emptyMap())) {
                var path = fs.getPath(name);
                if (java.nio.file.Files.exists(path)) return java.nio.file.Files.readString(path);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static long getPeakThreadCount() {
        try {
            return java.lang.management.ManagementFactory.getThreadMXBean().getPeakThreadCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int getPort() {
        HttpServer server = serverRef.get();
        return server != null ? server.getAddress().getPort() : DEFAULT_PORT;
    }

    public static ChaosConfig getConfig() {
        return new ChaosConfig();
    }

    public static void shutdown() {
        metricsBroadcaster.shutdownNow();
        HttpServer server = serverRef.getAndSet(null);
        if (server != null) {
            server.stop(0);
            started.set(false);
            System.out.println("[ChaosAgent] Stopped");
        }
    }

    public static class ChaosConfig {
        // Phase 2: Network Fault Injection
        public static volatile int latencyMs = 0;
        public static volatile boolean latencyEnabled = false;
        public static volatile boolean exceptionEnabled = false;
        public static volatile String exceptionType = "SocketTimeoutException";

        // Phase 3: Deep JVM Stressors
        public static volatile boolean pinningEnabled = false;
        public static volatile double pinningProbability = 0.1; // 10% of virtual threads
        public static volatile boolean memoryPressureEnabled = false;
        public static volatile int memoryPressureMb = 100; // MB to retain
        public static volatile boolean cpuBackpressureEnabled = false;
        public static volatile int cpuBackpressureIntensity = 50; // 0-100
    }
}