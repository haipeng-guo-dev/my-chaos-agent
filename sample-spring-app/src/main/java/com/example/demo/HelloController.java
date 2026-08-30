package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@RestController
public class HelloController {

    private final RestTemplate restTemplate = new RestTemplate();
    private final WebClient webClient = WebClient.create();

    @Autowired(required = false)
    private HttpBinFeignClient feignClient;

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Spring Boot at " + Instant.now();
    }

    @GetMapping("/external")
    public String callExternal() {
        try {
            String result = restTemplate.getForObject("https://httpbin.org/get", String.class);
            return "External call result (RestTemplate): " + (result != null ? result.substring(0, Math.min(200, result.length())) : "null");
        } catch (Exception e) {
            return "External call failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    @GetMapping("/external-webclient")
    public String callExternalWebClient() {
        try {
            String result = webClient.get()
                .uri("https://httpbin.org/get")
                .retrieve()
                .bodyToMono(String.class)
                .block(); // Block for demo simplicity
            return "External call result (WebClient): " + (result != null ? result.substring(0, Math.min(200, result.length())) : "null");
        } catch (Exception e) {
            return "External call failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    @GetMapping("/external-feign")
    public String callExternalFeign() {
        if (feignClient == null) {
            return "Feign client not available (enable @EnableFeignClients)";
        }
        try {
            String result = feignClient.get();
            return "External call result (Feign): " + (result != null ? result.substring(0, Math.min(200, result.length())) : "null");
        } catch (Exception e) {
            return "External call failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    @GetMapping("/health")
    public String health() {
        return "UP";
    }

    // Local endpoints (no external dependency) - ideal for chaos demos
    @GetMapping("/local")
    public String localCall() {
        return "Local response at " + Instant.now() + " on thread: " + Thread.currentThread();
    }

    @GetMapping("/local-delay")
    public String localDelay(@RequestParam(defaultValue = "100") int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return "Delayed " + ms + "ms on thread: " + Thread.currentThread();
    }

    @GetMapping("/local-error")
    public String localError(@RequestParam(defaultValue = "500") int status) {
        throw new RuntimeException("Simulated HTTP " + status);
    }

    // Virtual thread @Async endpoint - demonstrates carrier pinning
    @Autowired
    private AsyncService asyncService;

    @GetMapping("/virtual-async")
    public CompletableFuture<String> virtualAsync() {
        return asyncService.virtualTask(1);
    }

    @org.springframework.cloud.openfeign.FeignClient(name = "httpbin", url = "https://httpbin.org")
    interface HttpBinFeignClient {
        @org.springframework.web.bind.annotation.GetMapping("/get")
        String get();
    }
}