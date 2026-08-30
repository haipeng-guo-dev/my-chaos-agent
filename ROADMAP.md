# Project Roadmap: Next-Gen Zero-Code JVM Chaos Agent

This document outlines the strategic vision, architectural layout, and phased milestones for our zero-dependency, runtime-attachable Chaos Engineering agent designed for modern Java and Spring Cloud microservices.

## ?? Core Philosophy
- **Zero-Code Mutation:** No changes to application dependencies (`pom.xml` / `build.gradle`).
- **Zero-Classpath Pollution:** Isolated agent runtime server to prevent application framework conflicts.
- **Modern Java First:** Deep optimization for Java 21+ Virtual Threads (Project Loom) scenarios.
- **Frictionless Control:** Operates via a lightweight, embedded web console served directly out of the agent.

---

## ??? Architectural Blueprint

+-------------------------------------------------------------+|                     TARGET SPRING BOOT JAR                 ||                                                             ||  [Spring Cloud App / Tomcat / Netty]  <-- (Altered Bytes)   ||                                              |              |+----------------------------------------------|--------------+v+-------------------------------------------------------------+|                    YOUR CHAOS JAVA AGENT                    ||                                                             ||   +-------------------+     +----------------------------+  ||   |  Byte Buddy / ASM |     |  Isolated Embedded Server  |  ||   |  (Instrumentation)|     |  (e.g., Sun HttpServer)    |  ||   +-------------------+     +----------------------------+  ||                                              ^              |+----------------------------------------------|--------------+v+--------------------------------+|   HTML5 / Tailwind Dashboard   ||   (Served directly by Agent)   |+--------------------------------+


## ?? Implementation Milestones

### ?? Phase 1: Pipeline Foundations (Target: Weeks 1-2)
*Goal: Establish core agent bootstrap mechanics, fat-JAR construction, and dashboard delivery.*
- [ ] **Agent Premain/Agentmain Setup:** Implement core `premain` bootstrap class loaders.
- [ ] **Shaded Packaging Pipeline:** Configure build plugins (Maven Shade / Gradle Shadow) to isolate and relocate internal packages (e.g., Byte Buddy) to safeguard host classpaths.
- [ ] **Isolated Web Server Integration:** Embed a lightweight `com.sun.net.httpserver.HttpServer` listening on a configurable, non-disruptive fallback port (Default: `8090`).
- [ ] **Baseline Dashboard UI:** Write a single-page HTML5/Tailwind/Vanilla JS dashboard rendering live host platform telemetry (JVM Version, Active Memory footprint).

### ? Phase 2: Microservice Network Faults (Target: Weeks 3-5)
*Goal: Intercept outbound traffic layers to test fault tolerance components (Resilience4j/Feign).*
- [ ] **Network Client Hooking:** Map Byte Buddy interceptors across standard target components (`org.springframework.web.client.RestTemplate`, OpenFeign, HTTP Client wrappers).
- [ ] **Dynamic Latency Engine:** Implement a runtime-adjustable thread block engine reacting directly to dashboard millisecond slider configurations.
- [ ] **Exception Injection:** Expose toggles to intentionally mimic transport errors (e.g., forcing a `SocketTimeoutException` or HTTP `503 Service Unavailable`).

### ?? Phase 3: Advanced Deep-JVM Stressors (Target: Weeks 6-8)
*Goal: Deploy internal app-server mastery to introduce structural degradation testing.*
- [ ] **Virtual Thread (Loom) Carrier Pinning Simulator:** Orchestrate runtime execution paths that force `synchronized` lock constraints or JNI operations inside highly active threads to evaluate application resilience under high concurrent demand.
- [ ] **Managed Resource Degradation:** Construct safe, runtime-capped memory leak scenarios targeting specific structural collection types to trigger out-of-memory mitigation tests.
- [ ] **CPU Thermal Backpressure Simulator:** Inject targeted algorithmic computations to simulate infrastructure thrashing without fully crashing the underlying OS engine.

### ?? Phase 4: Production Polish & Open Source Launch (Target: Weeks 9-10)
*Goal: Refine the outward user experience to encourage rapid adoption and community engagement.*
- [ ] **Visual Impact Monitor:** Integrate real-time graphs displaying the delta between normal execution and active chaos metrics directly inside the web UI.
- [ ] **Documentation Strategy:** Deliver an elite `README.md` featuring a 3-step quickstart, clean visual configuration matrices, and clear contribution instructions (`CONTRIBUTING.md`).
- [ ] **Public Distribution Toolkit:** Create high-quality animated reference media demonstrating real-time UI manipulation affecting running target systems for developer community distribution.

---

## ?? Long-Term Backlog & Adaptations
- [ ] **Kubernetes Dynamic Injected Attachment Sidecars:** Provide automation scaffolding to dynamically mount the agent package across active production-like containers.
- [ ] **Config-as-Code Declarative Engine:** Support alternative startup parameter parsing via static YAML profiles alongside the interactive browser dashboard.
- [ ] **Spring Cloud Gateway Pipeline Interception:** Expand bytecode analysis capabilities to parse incoming perimeter proxy gateways directly.