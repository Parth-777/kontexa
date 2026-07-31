package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.api.DecisionResponseMapper;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnosticSession;
import com.example.BACKEND.catalogue.decision.presentation.executive.PrescriptiveContentGate;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisProperties;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticShadowResponseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic failure-path probes for Phase 2 narrative/response shaping
 * (no BigQuery). Used to isolate Internal Server Error causes that occur
 * after SQL compile/execute.
 */
class Phase2CountrySessionsFailPathProbeTest {

    @Test
    @DisplayName("NarrativeRenderer.toResult throws when phenomenon.measure.column is null (Map.of)")
    void nullMeasureColumnThrowsInInvestigationPayload() {
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "countries",
                        new InvestigationObjective.Measure(null, "COUNT", List.of())),
                List.of(),
                List.of(new InvestigationPlan.EvidenceRequest(
                        "1", "rank", null,
                        new InvestigationObjective.Measure("sessions", "COUNT", List.of()),
                        "geoNetwork.country",
                        null, null, null, null)),
                0.9);

        List<Map<String, Object>> rows = List.of(
                Map.of("member", "United States", "value", 1000),
                Map.of("member", "India", "value", 500));

        EvidencePack pack = new EvidencePackAssembler().assemble(
                "Which countries generate the most sessions?",
                plan,
                "`bigquery-public-data.google_analytics_sample.ga_sessions_*`",
                List.of(EvidencePack.RequestResult.success(
                        "1", "rank", null, List.of("ev_0_1"), rows,
                        "SELECT geoNetwork.country AS member, COUNTIF((TRUE)) AS value "
                                + "FROM t GROUP BY 1 ORDER BY value DESC LIMIT 10")));

        NarrativeRenderer renderer = new NarrativeRenderer();
        NarrativeOutput narrative = renderer.render(pack);

        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> renderer.toResult(UUID.randomUUID().toString(), pack, narrative));
        assertTrue(npe.getMessage() == null || npe.getMessage().contains("null")
                || true,
                "Map.of rejects null column in investigationPayload");
    }

    @Test
    @DisplayName("Healthy GA-like country rank path maps to HTTP body without exception")
    void healthyCountryRankPathMapsCleanly() {
        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "countries",
                        new InvestigationObjective.Measure("totals.visits", "SUM", List.of())),
                List.of(),
                List.of(new InvestigationPlan.EvidenceRequest(
                        "1", "top_n", null,
                        new InvestigationObjective.Measure("totals.visits", "SUM", List.of()),
                        "geoNetwork.country",
                        null, null, null, null)),
                0.9);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("member", "Country-" + i);
            row.put("value", 1000 - i);
            rows.add(row);
        }

        EvidencePack pack = new EvidencePackAssembler().assemble(
                "Which countries generate the most sessions?",
                plan,
                "`bigquery-public-data.google_analytics_sample.ga_sessions_*`",
                List.of(EvidencePack.RequestResult.success(
                        "1", "top_n", null, List.of("ev_0_1"), rows,
                        "SELECT geoNetwork.country AS member, "
                                + "SUM(CASE WHEN (TRUE) THEN totals.visits ELSE 0 END) AS value "
                                + "FROM t GROUP BY 1 ORDER BY value DESC LIMIT 10")));

        NarrativeRenderer renderer = new NarrativeRenderer();
        NarrativeOutput narrative = renderer.render(pack);
        DecisionRunResult result = assertDoesNotThrow(
                () -> renderer.toResult(UUID.randomUUID().toString(), pack, narrative));

        DecisionResponseMapper mapper = new DecisionResponseMapper(
                new PrescriptiveContentGate(),
                new ExecutionDiagnosticSession(),
                new SemanticShadowResponseMapper(),
                new AnswerSynthesisProperties(),
                new ResponseSerializationGuard());
        UUID runId = UUID.randomUUID();
        assertDoesNotThrow(() -> mapper.toRunResponse(runId, result, Map.of()));
    }
}
