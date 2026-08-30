package com.chaosagent.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.description.type.TypeDescription;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class NetworkFaultInterceptor {

    private static final List<TargetSpec> TARGETS = Arrays.asList(
        new TargetSpec(
            "org.springframework.web.client.RestTemplate",
            new String[]{"execute", "doExecute"},
            "RestTemplate"
        ),
        new TargetSpec(
            "org.springframework.web.reactive.function.client.DefaultWebClient",
            new String[]{"execute", "retrieve", "exchange"},
            "WebClient"
        ),
        new TargetSpec(
            "org.springframework.web.reactive.function.client.WebClient",
            new String[]{"execute", "retrieve", "exchange"},
            "WebClient"
        ),
        new TargetSpec(
            "org.springframework.web.reactive.function.client.ExchangeFunctions",
            new String[]{"exchange"},
            "WebClient"
        ),
        new TargetSpec(
            "org.springframework.http.client.reactive.ClientHttpConnector",
            new String[]{"connect"},
            "WebClient"
        ),
        new TargetSpec(
            "org.springframework.web.reactive.function.client.DefaultClientResponse",
            new String[]{"bodyToMono", "body"},
            "WebClient"
        ),
        new TargetSpec(
            "feign.Client",
            new String[]{"execute"},
            "Feign"
        ),
        new TargetSpec(
            "feign.SynchronousMethodHandler",
            new String[]{"executeAndDecode", "invoke"},
            "Feign"
        )
    );

    public static void install(Instrumentation inst) {
        // Build ElementMatcher for all target classes (lazy - evaluated at transform time)
        net.bytebuddy.matcher.ElementMatcher<TypeDescription> typeMatcher = buildTypeMatcher();

        AgentBuilder.Transformer transformer = (builder, typeDescription, classLoader, module, protectionDomain) -> {
            String className = typeDescription.getName();
            TargetSpec spec = findSpec(className);
            if (spec == null) return builder;

            net.bytebuddy.matcher.ElementMatcher<net.bytebuddy.description.method.MethodDescription> methodMatcher = 
                ElementMatchers.namedOneOf(spec.methodNames);

            System.out.println("[ChaosAgent] Transforming " + spec.displayName + " (" + className + ")");
            return builder.visit(Advice.to(ChaosAdvice.class).on(methodMatcher));
        };

        new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith("com.chaosagent."))
            .ignore(ElementMatchers.nameStartsWith("java."))
            .ignore(ElementMatchers.nameStartsWith("javax."))
            .ignore(ElementMatchers.nameStartsWith("sun."))
            .ignore(ElementMatchers.nameStartsWith("com.sun."))
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .type(typeMatcher)
            .transform(transformer)
            .installOn(inst);

        // Retransform already loaded target classes
        retransformLoadedClasses(inst);

        System.out.println("[ChaosAgent] Network fault interceptors installed for: RestTemplate, WebClient, Feign");
    }

    private static net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> buildTypeMatcher() {
        net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> matcher = ElementMatchers.none();
        for (TargetSpec spec : TARGETS) {
            matcher = matcher.or(ElementMatchers.named(spec.className));
        }
        return matcher;
    }

    private static TargetSpec findSpec(String className) {
        for (TargetSpec spec : TARGETS) {
            if (spec.className.equals(className)) return spec;
        }
        return null;
    }

    private static void retransformLoadedClasses(Instrumentation inst) {
        try {
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                for (TargetSpec spec : TARGETS) {
                    if (spec.className.equals(clazz.getName())) {
                        System.out.println("[ChaosAgent] Retransforming already loaded: " + clazz.getName());
                        inst.retransformClasses(clazz);
                        break;
                    }
                }
            }
        } catch (UnmodifiableClassException e) {
            System.err.println("[ChaosAgent] Initial retransform failed: " + e.getMessage());
        }
    }

    private static class TargetSpec {
        final String className;
        final String[] methodNames;
        final String displayName;

        TargetSpec(String className, String[] methodNames, String displayName) {
            this.className = className;
            this.methodNames = methodNames;
            this.displayName = displayName;
        }
    }

    public static class ChaosAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Origin String method) {
            // Direct static field access to avoid classloader issues
            int latencyMs = ChaosAgent.ChaosConfig.latencyMs;
            boolean latencyEnabled = ChaosAgent.ChaosConfig.latencyEnabled;
            boolean exceptionEnabled = ChaosAgent.ChaosConfig.exceptionEnabled;
            String exceptionType = ChaosAgent.ChaosConfig.exceptionType;
            
            if (latencyEnabled && latencyMs > 0) {
                try {
                    Thread.sleep(latencyMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (exceptionEnabled) {
                if ("SocketTimeoutException".equals(exceptionType)) {
                    throw new RuntimeException(new SocketTimeoutException("Chaos Agent: Simulated socket timeout"));
                } else if ("ConnectException".equals(exceptionType)) {
                    throw new RuntimeException(new ConnectException("Chaos Agent: Simulated connection refused"));
                } else if ("Http503".equals(exceptionType) || "Http500".equals(exceptionType) || "Http504".equals(exceptionType)) {
                    throw new RuntimeException("Chaos Agent: Simulated HTTP " + exceptionType.substring(4));
                }
            }
        }
    }
}