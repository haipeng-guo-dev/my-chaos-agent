package com.chaosagent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ShadingVerificationTest {

    @Test
    void agentJarContainsShadedByteBuddy() throws IOException {
        // Find the built agent JAR
        Path agentJar = findAgentJar();
        if (!Files.exists(agentJar)) {
            // Skip test if JAR not built yet (runs before package phase in CI)
            Assumptions.assumeTrue(false, "Agent JAR not built yet, skipping shading verification");
        }

        try (JarFile jar = new JarFile(agentJar.toFile())) {
            var entries = jar.stream()
                    .map(e -> e.getName())
                    .collect(Collectors.toSet());

            // Verify Byte Buddy is relocated
            assertThat(entries).anyMatch(name -> name.startsWith("com/chaosagent/shaded/bytebuddy/"));
            assertThat(entries).noneMatch(name -> name.startsWith("net/bytebuddy/") && !name.startsWith("net/bytebuddy/agent/"));
            
            // Verify shaded packages exist
            assertThat(entries).anyMatch(name -> name.startsWith("com/chaosagent/shaded/bytebuddy/ByteBuddy.class"));
            assertThat(entries).anyMatch(name -> name.startsWith("com/chaosagent/shaded/bytebuddy/agent/ByteBuddyAgent.class"));
            assertThat(entries).anyMatch(name -> name.startsWith("com/chaosagent/shaded/bytebuddy/dynamic/TypeResolutionStrategy.class"));
        }
    }

    @Test
    void agentJarHasCorrectManifest() throws IOException {
        Path agentJar = findAgentJar();
        if (!Files.exists(agentJar)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Agent JAR not built yet, skipping shading verification");
        }

        try (JarFile jar = new JarFile(agentJar.toFile())) {
            var manifest = jar.getManifest();
            assertThat(manifest).isNotNull();

            var mainAttributes = manifest.getMainAttributes();
            assertThat(mainAttributes.getValue("Premain-Class")).isEqualTo("com.chaosagent.agent.ChaosAgent");
            assertThat(mainAttributes.getValue("Agentmain-Class")).isEqualTo("com.chaosagent.agent.ChaosAgent");
            assertThat(mainAttributes.getValue("Can-Redefine-Classes")).isEqualTo("true");
            assertThat(mainAttributes.getValue("Can-Retransform-Classes")).isEqualTo("true");
        }
    }

    @Test
    void agentJarContainsDashboardResources() throws IOException {
        Path agentJar = findAgentJar();
        if (!Files.exists(agentJar)) {
            Assumptions.assumeTrue(false, "Agent JAR not built yet, skipping shading verification");
        }

        try (JarFile jar = new JarFile(agentJar.toFile())) {
            var entries = jar.stream()
                    .map(e -> e.getName())
                    .collect(Collectors.toSet());

            assertThat(entries).contains("index.html");
            assertThat(entries).contains("my-chaos-agent.png");
        }
    }

    @Test
    void agentJarContainsAgentClasses() throws IOException {
        Path agentJar = findAgentJar();
        if (!Files.exists(agentJar)) {
            Assumptions.assumeTrue(false, "Agent JAR not built yet, skipping shading verification");
        }

        try (JarFile jar = new JarFile(agentJar.toFile())) {
            var entries = jar.stream()
                    .map(e -> e.getName())
                    .collect(Collectors.toSet());

            assertThat(entries).contains("com/chaosagent/agent/ChaosAgent.class");
            assertThat(entries).contains("com/chaosagent/agent/ChaosConfig.class");
            assertThat(entries).contains("com/chaosagent/agent/NetworkFaultInterceptor.class");
            assertThat(entries).contains("com/chaosagent/agent/CarrierPinningInterceptor.class");
            assertThat(entries).contains("com/chaosagent/agent/MemoryPressureInterceptor.class");
            assertThat(entries).contains("com/chaosagent/agent/CpuBackpressureInterceptor.class");
        }
    }

    private Path findAgentJar() throws IOException {
        Path targetDir = Path.of("target");
        if (!Files.exists(targetDir)) {
            // Try parent directory (when running from project root)
            targetDir = Path.of("agent-core/target");
        }
        
        final Path resolvedDir = targetDir;
        if (!Files.exists(resolvedDir)) {
            // JAR not built yet - this test runs before package phase in CI
            // Return a path that will fail gracefully
            return resolvedDir.resolve("agent-core-0.1.0-SNAPSHOT.jar");
        }
        
        try (var stream = Files.list(resolvedDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("agent-core-.*\\.jar"))
                    .filter(p -> !p.getFileName().toString().contains("sources") && !p.getFileName().toString().contains("javadoc"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Agent JAR not found in " + resolvedDir.toAbsolutePath()));
        }
    }
}