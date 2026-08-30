# Contributing to my-chaos-agent

Thank you for your interest in contributing! This project aims to be the premier zero-code JVM chaos engineering agent. Every contribution — code, docs, issues, ideas — helps.

---

## 🏁 Quick Contribution Guide

### Prerequisites
- **Java 21+** (required for Virtual Thread support)
- **Maven 3.9+**
- **Git** (obviously)

### Setup
```bash
# 1. Fork & clone
git clone https://github.com/haipeng-guo-dev/my-chaos-agent.git
cd my-chaos-agent

# 2. Build everything (validates compile + shade)
mvn clean package -DskipTests

# 3. Run integration test manually
java -javaagent:agent-core/target/agent-core-0.1.0-SNAPSHOT.jar \
     -jar sample-spring-app/target/sample-spring-app-0.1.0-SNAPSHOT.jar
# Open http://localhost:8090 — verify dashboard loads
```

---

## 🧭 How to Contribute

### 1. Report Issues
- **Bug reports**: Use the issue template, include Java version, agent version, stack trace, steps to reproduce
- **Feature requests**: Describe the chaos scenario, target framework, expected behavior
- **Security issues**: Email directly (see SECURITY.md) — do not file public issues

### 2. Code Contributions

#### Branching
- `main` — protected, release-ready
- Feature branches: `feature/<short-description>`
- Bug fixes: `fix/<short-description>`
- Docs: `docs/<short-description>`

#### Coding Standards
| Aspect | Standard |
|--------|----------|
| **Language** | Java 21 (preview features OK if widely available) |
| **Formatting** | Google Java Format (enforced via Spotless in CI) |
| **Dependencies** | Agent-core: **zero external deps** (shaded Byte Buddy only) |
| **Logging** | `java.util.logging` only — no SLF4J/Logback in agent |
| **JSON** | Manual `String.format` — no Jackson/Gson in agent |
| **Threads** | `java.util.concurrent` — no Netty/Reactor in agent |

#### Adding a New Interceptor (Phase 2/3 pattern)
1. Create `NewStressorInterceptor.java` in `agent-core/src/main/java/com/chaosagent/agent/`
2. Follow the `TargetSpec` + `AgentBuilder` + `Advice` pattern
3. Add static config fields to `ChaosAgent.ChaosConfig`
4. Register in `ChaosAgent.start()` with try/catch logging
5. Add UI controls to `index.html` (dashboard)
6. Add metrics to SSE stream if applicable

#### Example: Minimal Interceptor Template
```java
public class NewStressorInterceptor {
    private static final List<TargetSpec> TARGETS = List.of(
        new TargetSpec("com.target.Class", new String[]{"method"}, "DisplayName")
    );

    public static void install(Instrumentation inst) {
        var matcher = buildTypeMatcher();
        var transformer = (builder, type, cl, module, pd) -> {
            var spec = findSpec(type.getName());
            if (spec == null) return builder;
            return builder.visit(Advice.to(AdviceClass.class).on(
                ElementMatchers.namedOneOf(spec.methodNames)));
        };
        new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith("com.chaosagent."))
            .disableClassFormatChanges()
            .type(matcher)
            .transform(transformer)
            .installOn(inst);
        retransformLoadedClasses(inst);
    }
    // ... TargetSpec, buildTypeMatcher, findSpec, retransformLoadedClasses
    
    public static class AdviceClass {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter() {
            if (!ChaosAgent.ChaosConfig.newFeatureEnabled) return;
            // chaos logic here
        }
    }
}
```

### 3. Dashboard Contributions
- Edit `agent-core/src/main/resources/index.html`
- Uses **Tailwind CDN** + **vanilla JS** — no build step
- Keep it single-file; no npm, no bundler
- Test in browser: open file directly or via agent

### 4. Documentation
- Update `README.md` for user-facing changes
- Update `ROADMAP.md` for milestone progress
- Add JavaDoc for public APIs (interceptor classes, config fields)

---

## ✅ Pull Request Checklist

Before submitting, verify:

- [ ] `mvn clean package` passes (root directory)
- [ ] Agent JAR builds with shade: `agent-core/target/agent-core-*-SNAPSHOT.jar` exists
- [ ] Sample app runs with agent attached (manual smoke test)
- [ ] Dashboard loads at `http://localhost:8090`
- [ ] New config fields added to `ChaosConfig` and `/api/config` endpoints
- [ ] New interceptors registered in `ChaosAgent.start()`
- [ ] Dashboard UI updated for new features
- [ ] No new dependencies added to `agent-core/pom.xml`
- [ ] No Spring/Netty/Jackson imports in `agent-core`
- [ ] Code formatted (IDE: Google Java Format)

---

## 🏗️ Project Structure for Contributors

```
agent-core/
├── pom.xml                          # Shade plugin config — DO NOT ADD DEPS HERE
└── src/main/
    ├── java/com/chaosagent/agent/
    │   ├── ChaosAgent.java          # premain/agentmain, HTTP server, REST API
    │   ├── ChaosConfig.java         # (inner class) All static volatile config
    │   ├── NetworkFaultInterceptor.java
    │   ├── CarrierPinningInterceptor.java
    │   ├── MemoryPressureInterceptor.java
    │   └── CpuBackpressureInterceptor.java
    └── resources/
        └── index.html               # Full dashboard (Tailwind + vanilla JS)

sample-spring-app/                   # Test target only — Spring deps OK here
```

---

## 🔬 Testing Expectations

We don't have a formal test suite yet (contributions welcome!). Current validation:

1. **Compile + Shade**: `mvn clean package` — verifies shading works
2. **Integration Smoke Test**: Run sample app with agent, hit endpoints, toggle dashboard
3. **Manual Chaos Verification**:
   ```bash
   # Test latency
   curl -X POST localhost:8090/api/config -d '{"latencyMs":1000,"latencyEnabled":true}' -H "Content-Type: application/json"
   time curl localhost:8080/external  # Should be ~1s slower
   
   # Test pinning
   curl -X POST localhost:8090/api/config -d '{"pinningEnabled":true,"pinningProbability":1.0}' -H "Content-Type: application/json"
   # Run high-concurrency virtual thread workload — observe carrier saturation
   ```

Future: Unit tests for Advice logic (using Byte Buddy AgentBuilder in-process), contract tests for REST API.

---

## 🚀 Release Process (Maintainers)

1. Update version in root `pom.xml` (`0.1.0-SNAPSHOT` → `0.1.0`)
2. `mvn clean deploy -Prelease` (signs, stages to Central)
3. Tag: `git tag v1.0.0 && git push origin v1.0.0`
4. GitHub Release with changelog
5. Bump to next `-SNAPSHOT`

---

## 💬 Communication

- **Issues**: GitHub Issues (bugs, features)
- **Discussions**: GitHub Discussions (questions, ideas, show-and-tell)
- **Security**: See `SECURITY.md`

---

## 📜 License

By contributing, you agree that your contributions will be licensed under the **MIT License** (same as the project). See [LICENSE](LICENSE).

---

## 🙏 Recognition

All contributors listed in `pom.xml` `<developers>` section and GitHub contributors graph. Significant features credited in release notes.

---

**Questions?** Open a Discussion or ping `@maintainer` in an issue. We're happy to help you land your first contribution!