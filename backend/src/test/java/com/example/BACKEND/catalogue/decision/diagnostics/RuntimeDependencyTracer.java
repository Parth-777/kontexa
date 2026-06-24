package com.example.BACKEND.catalogue.decision.diagnostics;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * Attaches method-entry tracing to selected production classes.
 */
public final class RuntimeDependencyTracer {

    private static final List<String> TARGETS = List.of(
            "com.example.BACKEND.catalogue.decision.clarification.DomainOntology",
            "com.example.BACKEND.catalogue.decision.planning.DomainAnalyticalDefaults",
            "com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy",
            "com.example.BACKEND.catalogue.decision.presentation.executive.BusinessSemanticAliases",
            "com.example.BACKEND.catalogue.decision.presentation.executive.RevenueCompositionAnalyzer",
            "com.example.BACKEND.catalogue.decision.candidate.CandidateAnalysisGenerator"
    );

    private static volatile boolean installed;

    private RuntimeDependencyTracer() {}

    public static void install() {
        if (installed) return;
        synchronized (RuntimeDependencyTracer.class) {
            if (installed) return;
            ByteBuddyAgent.install();
            Instrumentation inst = ByteBuddyAgent.getInstrumentation();
            AgentBuilder agent = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .ignore(ElementMatchers.nameStartsWith("net.bytebuddy.")
                            .or(ElementMatchers.nameStartsWith("jdk."))
                            .or(ElementMatchers.nameStartsWith("java."))
                            .or(ElementMatchers.nameStartsWith("sun.")))
                    .type(ElementMatchers.namedOneOf(TARGETS.toArray(String[]::new)))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(RuntimeDependencyMethodAdvice.class)
                                    .on(ElementMatchers.isMethod()
                                            .and(ElementMatchers.not(ElementMatchers.isTypeInitializer())))));
            agent.installOn(inst);
            installed = true;
        }
    }
}
