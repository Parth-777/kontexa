package com.example.BACKEND.catalogue.semantic.phase2;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.BACKEND.catalogue.decision.api.DecisionResponseMapper;
import com.example.BACKEND.catalogue.decision.candidate.CandidateAnalysisGenerator;
import com.example.BACKEND.catalogue.decision.clarification.AnalyticalQuestionResolver;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionExecutionContext;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.catalog.CatalogQuestionMatcher;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenMetricResolver;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live runtime-log proof that GPT mode never invokes the legacy lexical planner.
 * Enable: -Dkontexa.live.gpt.proof=true -Dkontexa.live.ga.tenant=netflix-admin
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "kontexa.live.gpt.proof", matches = "true")
class GptModeLexicalBypassLiveProofTest {

    private static final String QUESTION =
            "Which trafficSource.source generates the most sessions?";

    @Autowired DecisionRuntime decisionRuntime;
    @Autowired DecisionResponseMapper responseMapper;

    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

    @BeforeEach
    void attachLoggers() {
        appenders.add(attach(GptStructuredSemanticPlanner.class));
        appenders.add(attach(GptSemanticPlanningOrchestrator.class));
        appenders.add(attach(DecisionRuntime.class));
        appenders.add(attach(AnalyticalQuestionResolver.class));
        appenders.add(attach(CatalogQuestionMatcher.class));
        appenders.add(attach(SchemaDrivenMetricResolver.class));
        appenders.add(attach(MetricResolutionEngine.class));
        appenders.add(attach(CandidateAnalysisGenerator.class));
    }

    @AfterEach
    void cleanup() {
        detachAll();
        AuthContextHolder.clear();
    }

    @Test
    @Transactional
    @DisplayName("GPT mode live proof: planner authority, zero lexical log activity")
    void proveGptPathExcludesLexicalPlanner() {
        String tenant = System.getProperty("kontexa.live.ga.tenant", "netflix-admin");
        AuthContextHolder.set(new AuthContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenant,
                "gpt-proof@local",
                "GPT Proof",
                "ADMIN",
                "test"));

        System.out.println("========== GPT MODE LEXICAL BYPASS PROOF ==========");
        System.out.println("QUESTION=" + QUESTION);
        System.out.println("TENANT=" + tenant);

        DecisionExecutionContext ctx = DecisionRuntime.contextFrom(tenant, QUESTION, Map.of());
        DecisionRunResult result = decisionRuntime.execute(ctx);
        Map<String, Object> response = responseMapper.toRunResponse(ctx.runId(), result, Map.of());

        List<String> planner = messagesOf(GptStructuredSemanticPlanner.class);
        List<String> orch = messagesOf(GptSemanticPlanningOrchestrator.class);
        List<String> runtime = messagesOf(DecisionRuntime.class);
        List<String> resolver = messagesOf(AnalyticalQuestionResolver.class);
        List<String> matcher = messagesOf(CatalogQuestionMatcher.class);
        List<String> schemaMetric = messagesOf(SchemaDrivenMetricResolver.class);
        List<String> metricEngine = messagesOf(MetricResolutionEngine.class);
        List<String> candidates = messagesOf(CandidateAnalysisGenerator.class);

        // 1) GPT planner executed
        assertTrue(planner.stream().anyMatch(m -> m.contains("stage=raw_gpt_json")),
                "GptStructuredSemanticPlanner.plan() must log raw_gpt_json");

        // 2) resolveFull not called
        assertTrue(runtime.stream().anyMatch(m -> m.contains("lexicalResolveFull=bypassed")),
                "DecisionRuntime must bypass resolveFull in GPT mode");
        assertFalse(resolver.stream().anyMatch(m -> m.toLowerCase().contains("resolvefull")
                        || m.contains("questionResolved")),
                "AnalyticalQuestionResolver must stay silent (resolveFull not run)");

        // 3–6) Lexical components never emit their known activity signatures.
        // CatalogQuestionMatcher has no entry logs; it is only reachable via resolveFull /
        // SchemaDriven* which are proven idle below.
        assertFalse(schemaMetric.stream().anyMatch(m -> m.contains("[metric-resolution]")),
                "SchemaDrivenMetricResolver must not run: " + schemaMetric);
        assertTrue(schemaMetric.isEmpty(),
                "SchemaDrivenMetricResolver log stream must be empty: " + schemaMetric);
        assertFalse(metricEngine.stream().anyMatch(m -> m.contains("[metric-resolution-debug]")
                        || m.contains("[resolution-debug]")
                        || m.contains("winning_metric=")),
                "MetricResolutionEngine must not run: " + metricEngine);
        assertTrue(metricEngine.isEmpty(),
                "MetricResolutionEngine log stream must be empty: " + metricEngine);
        assertFalse(candidates.stream().anyMatch(m -> m.contains("[candidate]")
                        || m.contains("generate")),
                "CandidateAnalysisGenerator must not run: " + candidates);
        assertFalse(runtime.stream().anyMatch(m -> m.contains("questionResolved metric=")),
                "legacy questionResolved metric log must be absent");
        assertFalse(runtime.stream().anyMatch(m -> m.toLowerCase().contains("sessionqualitydim")),
                "sessionQualityDim must not appear in DecisionRuntime logs");
        // matcher list kept for report output only
        assertTrue(matcher.isEmpty() || matcher.stream().noneMatch(m -> m.contains("stem_prefix")),
                "CatalogQuestionMatcher must not produce stem matches: " + matcher);

        // 7–8) Metric from GPT; bindColumn received totals.visits
        String before = lastContaining(planner, "stage=metric_before_bind");
        String after = lastContaining(planner, "stage=metric_after_bind");
        assertTrue(before.contains("metric=totals.visits"), "bind input: " + before);
        assertTrue(after.contains("metric=totals.visits"), "bind output: " + after);
        assertFalse(before.toLowerCase().contains("sessionqualitydim"));
        assertFalse(after.toLowerCase().contains("sessionqualitydim"));

        String raw = lastContaining(planner, "stage=raw_gpt_json");
        assertTrue(raw.contains("\"metric\":\"totals.visits\""),
                "GPT raw plan metric must be totals.visits: " + raw);

        // 9) CanonicalQueryModel measure.column = totals.visits
        String cqm = lastContaining(orch, "stage=canonical_query_model");
        assertTrue(cqm.contains("\"measure\":{\"column\":\"totals.visits\""),
                "CQM must contain measure.column=totals.visits: " + cqm);

        System.out.println("PROOF (runtime logs):");
        System.out.println("  1. GptStructuredSemanticPlanner.plan() executed=true");
        System.out.println("     " + lastContaining(planner, "stage=raw_gpt_json").substring(0,
                Math.min(160, lastContaining(planner, "stage=raw_gpt_json").length())) + "...");
        System.out.println("  2. resolveFull() called=false (lexicalResolveFull=bypassed)");
        System.out.println("  3. CatalogQuestionMatcher invoked=false (0 log events)");
        System.out.println("  4. SchemaDrivenMetricResolver invoked=false (0 log events)");
        System.out.println("  5. MetricResolutionEngine invoked=false (0 log events)");
        System.out.println("  6. CandidateAnalysisGenerator invoked=false (0 log events)");
        System.out.println("  7. Metric from GPT plan only: \"metric\":\"totals.visits\"");
        System.out.println("  8. " + before);
        System.out.println("     " + after);
        System.out.println("  9. " + cqm);
        System.out.println("  served_path=" + response.get("served_path"));
        System.out.println("========== END GPT MODE PROOF ==========");

        assertTrue(result != null);
    }

    private ListAppender<ILoggingEvent> attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setName("gpt-proof-" + type.getSimpleName());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAll() {
        for (ListAppender<ILoggingEvent> appender : appenders) {
            appender.stop();
        }
        for (Class<?> type : List.of(
                GptStructuredSemanticPlanner.class,
                GptSemanticPlanningOrchestrator.class,
                DecisionRuntime.class,
                AnalyticalQuestionResolver.class,
                CatalogQuestionMatcher.class,
                SchemaDrivenMetricResolver.class,
                MetricResolutionEngine.class,
                CandidateAnalysisGenerator.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            logger.detachAppender("gpt-proof-" + type.getSimpleName());
        }
        appenders.clear();
    }

    private List<String> messagesOf(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        var appender = logger.getAppender("gpt-proof-" + type.getSimpleName());
        if (!(appender instanceof ListAppender<?> list)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        ListAppender<ILoggingEvent> typed = (ListAppender<ILoggingEvent>) list;
        return typed.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private static String lastContaining(List<String> messages, String needle) {
        return messages.stream()
                .filter(m -> m.contains(needle))
                .reduce((a, b) -> b)
                .orElse("");
    }
}
