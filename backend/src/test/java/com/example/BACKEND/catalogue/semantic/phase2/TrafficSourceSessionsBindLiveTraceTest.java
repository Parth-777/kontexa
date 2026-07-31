package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.api.DecisionResponseMapper;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionExecutionContext;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Live bind/canonical trace for one GA ranking question.
 * Enable: -Dkontexa.live.bind.trace=true -Dkontexa.live.ga.tenant=netflix-admin
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "kontexa.live.bind.trace", matches = "true")
class TrafficSourceSessionsBindLiveTraceTest {

    private static final String QUESTION =
            "Which trafficSource.source generates the most sessions?";

    @Autowired DecisionRuntime decisionRuntime;
    @Autowired DecisionResponseMapper responseMapper;

    @AfterEach
    void cleanup() {
        AuthContextHolder.clear();
    }

    @Test
    @Transactional
    @DisplayName("Live GPT bind/canonical trace for trafficSource.source sessions question")
    void traceBindAndCanonical() {
        String tenant = System.getProperty("kontexa.live.ga.tenant", "netflix-admin");
        AuthContextHolder.set(new AuthContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                tenant,
                "bind-trace@local",
                "Bind Trace",
                "ADMIN",
                "test"));

        System.out.println("========== LIVE BIND TRACE ==========");
        System.out.println("QUESTION=" + QUESTION);
        System.out.println("TENANT=" + tenant);
        System.out.println("Watch logs for [planner-pipeline-trace] stages:");
        System.out.println("  raw_gpt_json");
        System.out.println("  metric_before_bind");
        System.out.println("  bindColumn_failed (if any)");
        System.out.println("  metric_after_bind");
        System.out.println("  structured_semantic_plan");
        System.out.println("  canonical_query_model");
        System.out.println("  semantic_plan_validation / execution_mode");
        System.out.println("=====================================");

        DecisionExecutionContext ctx = DecisionRuntime.contextFrom(tenant, QUESTION, Map.of());
        DecisionRunResult result = decisionRuntime.execute(ctx);
        Map<String, Object> response = responseMapper.toRunResponse(ctx.runId(), result, Map.of());

        System.out.println("runId=" + ctx.runId());
        System.out.println("served_path=" + response.get("served_path"));
        System.out.println("planner_execution_mode=" + response.get("planner_execution_mode"));
        System.out.println("execution_path=" + response.get("execution_path"));
        System.out.println("========== END LIVE BIND TRACE ==========");
    }
}
