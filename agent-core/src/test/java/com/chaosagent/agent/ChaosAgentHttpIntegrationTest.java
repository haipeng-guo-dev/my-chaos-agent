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
        assertThat(response.body()).contains("\"started\":true");
    }

    @Test
    void configGetEndpointReturnsDefaults() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/config"))
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"latencyMs\":0");
        assertThat(response.body()).contains("\"latencyEnabled\":false");
        assertThat(response.body()).contains("\"exceptionEnabled\":false");
        assertThat(response.body()).contains("\"exceptionType\":\"SocketTimeoutException\"");
        assertThat(response.body()).contains("\"pinningEnabled\":false");
        assertThat(response.body()).contains("\"pinningProbability\":0.1");
        assertThat(response.body()).contains("\"memoryPressureEnabled\":false");
        assertThat(response.body()).contains("\"memoryPressureMb\":100");
        assertThat(response.body()).contains("\"cpuBackpressureEnabled\":false");
        assertThat(response.body()).contains("\"cpuBackpressureIntensity\":50");
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
        assertThat(getResponse.body()).contains("\"latencyMs\":2000");
        assertThat(getResponse.body()).contains("\"latencyEnabled\":true");
        assertThat(getResponse.body()).contains("\"exceptionEnabled\":true");
        assertThat(getResponse.body()).contains("\"exceptionType\":\"ConnectException\"");
        assertThat(getResponse.body()).contains("\"pinningEnabled\":true");
        assertThat(getResponse.body()).contains("\"pinningProbability\":0.25");
        assertThat(getResponse.body()).contains("\"memoryPressureEnabled\":true");
        assertThat(getResponse.body()).contains("\"memoryPressureMb\":200");
        assertThat(getResponse.body()).contains("\"cpuBackpressureEnabled\":true");
        assertThat(getResponse.body()).contains("\"cpuBackpressureIntensity\":75");
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
                .GET()
                .build();

        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        // SSE endpoint returns 200 and starts streaming
        assertThat(response.statusCode()).isEqualTo(200);
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
        assertThat(response.body()).contains("\"version\":1");
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
}