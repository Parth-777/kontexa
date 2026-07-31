package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidence-centric trace: mixed EvidenceRequests → compile → pack → narrative.
 * No contrast-root orchestration.
 */
class InvestigationRuntimePaymentTypeTraceTest {

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void traceMixedEvidencePlanWithoutContrastRoot() throws Exception {
        String question = "Why do payment type 1 users tip more than payment type 2?";
        String tableRef = "yellow_taxi_trips";

        InvestigationPlan plan = new InvestigationPlan(
                new InvestigationPlan.Phenomenon(
                        "tip difference by payment type",
                        new InvestigationObjective.Measure("tip_amount", "AVG", List.of())),
                List.of(),
                List.of(
                        new InvestigationPlan.EvidenceRequest(
                                "pt1_avg", "slice_metric", "FOCAL",
                                new InvestigationObjective.Measure("tip_amount", "AVG", List.of()),
                                null,
                                sliceFilter("payment_type", "1"),
                                null, null, null),
                        new InvestigationPlan.EvidenceRequest(
                                "pt2_avg", "slice_metric", "BASELINE",
                                new InvestigationObjective.Measure("tip_amount", "AVG", List.of()),
                                null,
                                sliceFilter("payment_type", "2"),
                                null, null, null),
                        new InvestigationPlan.EvidenceRequest(
                                "by_region", "metric_by_dimension", null,
                                new InvestigationObjective.Measure("tip_amount", "AVG", List.of()),
                                "region",
                                null,
                                sliceFilter("payment_type", "1"),
                                sliceFilter("payment_type", "2"),
                                null),
                        new InvestigationPlan.EvidenceRequest(
                                "dist_region", "distribution", null,
                                null, "region", null, null, null, null),
                        new InvestigationPlan.EvidenceRequest(
                                "unsupported_kind", "funnel", null,
                                null, null, null, null, null, null)
                ),
                0.92);

        EvidenceRequestSqlCompiler compiler =
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());
        EvidenceRequestSqlCompiler.CompiledProgram compiled = compiler.compile(plan, tableRef);

        System.out.println("=== USER QUESTION ===");
        System.out.println(question);
        System.out.println();
        System.out.println("=== INVESTIGATION PLAN ===");
        System.out.println(json.writeValueAsString(plan));
        System.out.println();
        System.out.println("=== COMPILED SQL / FAILURES ===");
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            if (cr.ok()) {
                System.out.println("[" + cr.spec().key() + "] " + cr.request().kind());
                System.out.println(cr.spec().sql());
            } else {
                System.out.println("[FAILED] " + cr.request().requestId() + " kind="
                        + cr.request().kind() + " → " + cr.error());
            }
            System.out.println();
        }

        Map<String, QueryResult> mockResults = new LinkedHashMap<>();
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            if (!cr.ok()) {
                continue;
            }
            String id = cr.request().requestId();
            if ("pt1_avg".equals(id)) {
                mockResults.put(cr.spec().key(), new QueryResult(
                        cr.spec().key(), List.of(Map.of("value", 3.14)), 5L));
            } else if ("pt2_avg".equals(id)) {
                mockResults.put(cr.spec().key(), new QueryResult(
                        cr.spec().key(), List.of(Map.of("value", 1.82)), 5L));
            } else if ("by_region".equals(id)) {
                mockResults.put(cr.spec().key(), new QueryResult(
                        cr.spec().key(),
                        List.of(
                                Map.of("member", "Manhattan", "focal_value", 3.48, "baseline_value", 2.05),
                                Map.of("member", "Brooklyn", "focal_value", 2.91, "baseline_value", 1.62)),
                        8L));
            } else if ("dist_region".equals(id)) {
                mockResults.put(cr.spec().key(), new QueryResult(
                        cr.spec().key(),
                        List.of(
                                Map.of("member", "Manhattan", "count", 1200),
                                Map.of("member", "Brooklyn", "count", 800)),
                        8L));
            }
        }

        List<EvidencePack.RequestResult> requestResults = new ArrayList<>();
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            if (!cr.ok()) {
                requestResults.add(EvidencePack.RequestResult.failure(
                        cr.request().requestId(), cr.request().kind(), cr.request().role(), cr.error()));
                continue;
            }
            QueryResult qr = mockResults.get(cr.spec().key());
            requestResults.add(EvidencePack.RequestResult.success(
                    cr.request().requestId(),
                    cr.request().kind(),
                    cr.request().role(),
                    List.of(cr.spec().key()),
                    qr != null ? qr.rows() : List.of()));
        }

        EvidencePackAssembler packAssembler = new EvidencePackAssembler();
        EvidencePack pack = packAssembler.assemble(question, plan, tableRef, requestResults);
        NarrativeRenderer narrativeRenderer = new NarrativeRenderer();
        NarrativeOutput narrative = narrativeRenderer.render(pack);
        DecisionRunResult result = narrativeRenderer.toResult("trace-run-001", pack, narrative);

        System.out.println("=== EVIDENCE PACK ===");
        System.out.println(json.writeValueAsString(pack));
        System.out.println();
        System.out.println("=== NARRATIVE ===");
        System.out.println(json.writeValueAsString(narrative));
        System.out.println();
        System.out.println("=== DECISION RUN RESULT ===");
        System.out.println("title:      " + result.insight().title());
        System.out.println("narrative:  " + result.insight().narrative());
        System.out.println("confidence: " + result.analytical().confidence());

        assertEquals(5, pack.coverage().requestsPlanned());
        assertEquals(4, pack.coverage().requestsSucceeded());
        assertEquals(1, pack.coverage().requestsFailed());
        assertTrue(pack.requestResults().stream()
                .anyMatch(rr -> "funnel".equals(rr.kind()) && !rr.ok()));
        assertFalse(narrative.headline().isBlank());
        assertTrue(narrative.headline().contains("3.14") && narrative.headline().contains("1.82"));
        assertTrue(narrative.explanation().contains("FOCAL") || narrative.explanation().contains("3.14"));
        assertTrue(narrative.explanation().contains("unsupported")
                || narrative.explanation().contains("funnel"));
    }

    private static InvestigationObjective.Slice sliceFilter(String column, String value) {
        return new InvestigationObjective.Slice(List.of(
                new InvestigationObjective.Constraint(
                        new InvestigationObjective.Filter(column, "=", value),
                        null, null, null)));
    }
}
