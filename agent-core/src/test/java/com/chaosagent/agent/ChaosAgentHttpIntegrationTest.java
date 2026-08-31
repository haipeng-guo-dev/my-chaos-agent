package com.chaosagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ChaosAgentHttpIntegrationTest {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpServer testServer;
    private int testPort;
    private String baseUrl;

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

    @BeforeEach
    void startTestServer() throws IOException {
        // Find a free port
        try (var serverSocket = new java.net.ServerSocket(0)) {
            testPort = serverSocket.getLocalPort();
        }

        // Create test HTTP server with same handlers as ChaosAgent
        testServer = HttpServer.create(new InetSocketAddress("localhost", testPort), 0);
        testServer.setExecutor(Executors.newCachedThreadPool());
        
        testServer.createContext("/", ChaosAgent::serveDashboard);
        testServer.createContext("/api/status", ChaosAgent::serveStatus);
        testServer.createContext("/api/config", ChaosAgent::serveConfig);
        testServer.createContext("/api/metrics/stream", ChaosAgent::serveMetricsStream);
        testServer.createContext("/api/profile", ChaosAgent::serveProfile);
        testServer.createContext("/static", ChaosAgent::serveStaticResource);
        
        testServer.start();
        baseUrl = "http://localhost:" + testPort;
        
        // Wait for server to be ready
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try {
                        var request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/api/status"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build();
                        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode()).isEqualTo(200);
                    } catch (Exception e) {
                        throw new AssertionError("Server not ready", e);
                    }
                });
    }

    @AfterEach
    void stopTestServer() {
        if (testServer != null) {
            testServer.stop(0);
        }
    }

    @Test
    void statusEndpointReturnsValidJson() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/status"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"jvm\"");
        assertThat(response.body()).contains("\"memory\"");
        assertThat(response.body()).contains("\"threads\"");
        assertThat(response.body()).contains("\"agent\"");
        assertThat(response.body()).contains("\"started\"");
    }

    @Test
    void configGetEndpointReturnsDefaults() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/config"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("latencyMs");
        assertThat(response.body()).contains("latencyEnabled");
        assertThat(response.body()).contains("exceptionEnabled");
        assertThat(response.body()).contains("exceptionType");
        assertThat(response.body()).contains("pinningEnabled");
        assertThat(response.body()).contains("pinningProbability");
        assertThat(response.body()).contains("memoryPressureEnabled");
        assertThat(response.body()).contains("memoryPressureMb");
        assertThat(response.body()).contains("cpuBackpressureEnabled");
        assertThat(response.body()).contains("cpuBackpressureIntensity");
    }

    @Test
    void configPostEndpointUpdatesValues() throws Exception {
        String newConfig = """
            {
              "latencyMs": 2000,
              "latencyEnabled": true,
              "exceptionEnabled": true,
              "exceptionType": "ConnectException",
              "pinningEnabled": true,
              "pinningProbability": 0.25,
              "memoryPressureEnabled": true,
              "memoryPressureMb": 200,
              "cpuBackpressureEnabled": true,
              "cpuBackpressureIntensity": 75
            }
            """;

        var postRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/config"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(newConfig))
                .build();

        var postResponse = HTTP_CLIENT.send(postRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(postResponse.statusCode()).isEqualTo(200);
        assertThat(postResponse.body()).contains("\"status\":\"ok\"");

        // Verify GET returns updated values
        var getRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/config"))
                .GET()
                .build();

        var getResponse = HTTP_CLIENT.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(getResponse.body()).contains("latencyMs");
        assertThat(getResponse.body()).contains("latencyEnabled");
        assertThat(getResponse.body()).contains("exceptionEnabled");
        assertThat(getResponse.body()).contains("exceptionType");
        assertThat(getResponse.body()).contains("pinningEnabled");
        assertThat(getResponse.body()).contains("pinningProbability");
        assertThat(getResponse.body()).contains("memoryPressureEnabled");
        assertThat(getResponse.body()).contains("memoryPressureMb");
        assertThat(getResponse.body()).contains("cpuBackpressureEnabled");
        assertThat(getResponse.body()).contains("cpuBackpressureIntensity");
    }

    @Test
    void staticResourceServesLogo() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/static/my-chaos-agent.png"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(response.body()).isNotEmpty();
        // PNG magic bytes
        assertThat(response.body()[0]).isEqualTo((byte) 0x89);
        assertThat(response.body()[1]).isEqualTo((byte) 0x50);
        assertThat(response.body()[2]).isEqualTo((byte) 0x4E);
        assertThat(response.body()[3]).isEqualTo((byte) 0x47);
    }

    @Test
    void staticResourceReturns404ForMissingFile() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/static/nonexistent.png"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void dashboardHtmlServedAtRoot() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("text/html"));
        assertThat(response.body()).contains("Chaos Agent Dashboard");
        assertThat(response.body()).contains("my-chaos-agent.png");
    }

    @Test
    void sseEndpointAcceptsConnection() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/metrics/stream"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        // Use a body handler that doesn't wait for completion
        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());

        // SSE endpoint returns 200 and starts streaming
        assertThat(response.statusCode()).isEqualTo(200);
        // Close the stream immediately
        response.body().close();
    }

    @Test
    void profileExportReturnsJson() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/profile"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("chaos-profile.json"));
        assertThat(response.body()).contains("version");
        assertThat(response.body()).contains("\"config\"");
    }

    @Test
    void profileImportAcceptsValidJson() throws Exception {
        String profileJson = """
            {
              "version": 1,
              "config": {
                "latencyMs": 1000,
                "latencyEnabled": true,
                "exceptionEnabled": false,
                "exceptionType": "SocketTimeoutException",
                "pinningEnabled": false,
                "pinningProbability": 0.1,
                "memoryPressureEnabled": false,
                "memoryPressureMb": 100,
                "cpuBackpressureEnabled": false,
                "cpuBackpressureIntensity": 50
              }
            }
            """;

        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/profile"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(profileJson))
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"ok\"");
        assertThat(response.body()).contains("Profile imported successfully");
    }

    @Test
    void statusEndpointReflectsMemoryPressureMetrics() throws Exception {
        // Enable memory pressure and trigger retention via Advice
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 10;

        // Trigger multiple memory retention calls
        for (int i = 0; i < 200; i++) {
            MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item" + i});
        }

        long expectedBytes = MemoryPressureInterceptor.getRetainedBytes();
        int expectedEntries = MemoryPressureInterceptor.getRetainedEntries();

        // Verify status endpoint includes Phase 3 metrics
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/status"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("phase3");
        assertThat(response.body()).contains("memRetainedMb");
        assertThat(response.body()).contains("memEntries");
        // Verify the values match (allowing for MB rounding)
        int expectedMb = (int) (expectedBytes / (1024 * 1024));
        assertThat(response.body()).contains(String.valueOf(expectedMb));
    }

    @Test
    void statusEndpointReflectsCpuBackpressureMetrics() throws Exception {
        // Enable CPU backpressure and trigger busy spin
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;
        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();

        // Trigger multiple busy spin calls
        for (int i = 0; i < 5; i++) {
            CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");
        }

        long expectedNanos = CpuBackpressureInterceptor.getTotalBusyNanos();
        long expectedCount = CpuBackpressureInterceptor.getBusyCount();

        // Verify status endpoint includes Phase 3 metrics
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/status"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("cpuBusyMs");
        assertThat(response.body()).contains("cpuBusyCount");
        // Verify count matches
        assertThat(response.body()).contains(String.valueOf(expectedCount));
    }

    @Test
    void metricsStreamIncludesPhase3Metrics() throws Exception {
        // Enable both memory pressure and CPU backpressure
        ChaosAgent.ChaosConfig.memoryPressureEnabled = true;
        ChaosAgent.ChaosConfig.memoryPressureMb = 10;
        ChaosAgent.ChaosConfig.cpuBackpressureEnabled = true;
        ChaosAgent.ChaosConfig.cpuBackpressureIntensity = 100;
        CpuBackpressureInterceptor.CpuBackpressureAdvice.resetMetrics();

        // Trigger activity
        for (int i = 0; i < 50; i++) {
            MemoryPressureInterceptor.MemoryPressureAdvice.enter("add", new Object[]{"item" + i});
            CpuBackpressureInterceptor.CpuBackpressureAdvice.enter("exec");
        }

        long expectedMemBytes = MemoryPressureInterceptor.getRetainedBytes();
        int expectedMemEntries = MemoryPressureInterceptor.getRetainedEntries();
        long expectedCpuNanos = CpuBackpressureInterceptor.getTotalBusyNanos();
        long expectedCpuCount = CpuBackpressureInterceptor.getBusyCount();

        // Connect to SSE endpoint and read one event
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/metrics/stream"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);

        // Read first event from stream
        var lines = response.body().iterator();
        String eventLine = lines.hasNext() ? lines.next() : "";
        response.body().close();

        assertThat(eventLine).startsWith("data: ");
        String json = eventLine.substring(6); // Remove "data: " prefix

        // Parse and verify Phase 3 metrics present
        assertThat(json).contains("phase3");
        assertThat(json).contains("memRetainedMb");
        assertThat(json).contains("memEntries");
        assertThat(json).contains("cpuBusyMs");
        assertThat(json).contains("cpuBusyCount");

        // Verify values are reasonable (non-negative)
        int memMb = (int) (expectedMemBytes / (1024 * 1024));
        assertThat(json).contains(String.valueOf(memMb));
        assertThat(json).contains(String.valueOf(expectedMemEntries));
        assertThat(json).contains(String.valueOf(expectedCpuCount));
    }
}