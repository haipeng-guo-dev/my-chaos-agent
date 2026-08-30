package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

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

    @org.springframework.cloud.openfeign.FeignClient(name = "httpbin", url = "https://httpbin.org")
    interface HttpBinFeignClient {
        @org.springframework.web.bind.annotation.GetMapping("/get")
        String get();
    }
}