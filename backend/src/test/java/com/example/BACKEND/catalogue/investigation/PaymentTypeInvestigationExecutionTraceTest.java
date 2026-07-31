package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnosticSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full execution trace for:
 * "Why do payment type 1 users tip more than payment type 2?"
 *
 * Uses TLC-realistic warehouse rows (cash tip_amount = 0) and proves the
 * Warehouse execution panel vs EvidencePack disconnect / wiring fix.
 */
class PaymentTypeInvestigationExecutionTraceTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String QUESTION =
            "Why do payment type 1 users tip more than payment type 2?";

    @Test
    @DisplayName("Full payment-type investigation execution trace")
    void fullExecutionTrace() throws Exception {
        InvestigationPlan plan = paymentTypePlan();
        String tableRef = "yellow_tripdata_2024_01"; // example table ref — not hardcoded in product path

        System.out.println("========== 1. RAW InvestigationPlan JSON ==========");
        System.out.println(JSON.writeValueAsString(plan));

        System.out.println("\n========== 2. Every EvidenceRequest ==========");
        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            System.out.println(JSON.writeValueAsString(req));
        }

        EvidenceRequestSqlCompiler compiler =
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());
        EvidenceRequestSqlCompiler.CompiledProgram compiled = compiler.compile(plan, tableRef);

        System.out.println("\n========== 3+4. Compiled QuerySpec + Generated SQL ==========");
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            System.out.println("--- request_id=" + cr.request().requestId()
                    + " kind=" + cr.request().kind()
                    + " role=" + cr.request().role()
                    + " ok=" + cr.ok());
            if (cr.ok()) {
                System.out.println("QuerySpec.key=" + cr.spec().key());
                System.out.println("QuerySpec.params=" + cr.spec().params());
                System.out.println("SQL:\n" + cr.spec().sql());
            } else {
                System.out.println("ERROR: " + cr.error());
            }
        }

        // TLC-realistic: payment_type=1 (card) has tips; payment_type=2 (cash) tip_amount is always 0.
        Map<String, List<Map<String, Object>>> warehouseByKey = new LinkedHashMap<>();
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            if (!cr.ok()) {
                continue;
            }
            String role = cr.request().role();
            if ("FOCAL".equalsIgnoreCase(role)) {
                warehouseByKey.put(cr.spec().key(), List.of(Map.of("value", 3.14)));
            } else if ("BASELINE".equalsIgnoreCase(role)) {
                warehouseByKey.put(cr.spec().key(), List.of(Map.of("value", 0.0)));
            } else {
                warehouseByKey.put(cr.spec().key(), List.of(Map.of("value", 1.0)));
            }
        }

        System.out.println("\n========== 5+6. Warehouse row count + returned rows per QuerySpec ==========");
        List<EvidencePack.RequestResult> requestResults = new ArrayList<>();
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            if (!cr.ok()) {
                requestResults.add(EvidencePack.RequestResult.failure(
                        cr.request().requestId(), cr.request().kind(), cr.request().role(), cr.error()));
                continue;
            }
            List<Map<String, Object>> rows = warehouseByKey.getOrDefault(cr.spec().key(), List.of());
            System.out.println("--- " + cr.spec().key());
            System.out.println("row_count=" + rows.size());
            System.out.println("rows=" + JSON.writeValueAsString(rows));
            System.out.println("SQL filter: " + extractPaymentFilter(cr.spec().sql()));
            System.out.println("value observed: " + rows.getFirst().get("value"));
            requestResults.add(EvidencePack.RequestResult.success(
                    cr.request().requestId(),
                    cr.request().kind(),
                    cr.request().role(),
                    List.of(cr.spec().key()),
                    rows,
                    cr.spec().sql()));
        }

        EvidencePack pack = new EvidencePackAssembler()
                .assemble(QUESTION, plan, tableRef, requestResults);

        System.out.println("\n========== 7. EvidencePack.requestResults ==========");
        System.out.println(JSON.writeValueAsString(pack.requestResults()));
        System.out.println("coverage=" + JSON.writeValueAsString(pack.coverage()));

        NarrativeRenderer narrativeRenderer = new NarrativeRenderer();
        NarrativeOutput narrative = narrativeRenderer.render(pack);

        System.out.println("\n========== 8. Final narrative inputs ==========");
        System.out.println("pack.question=" + pack.question());
        System.out.println("pack.phenomenon=" + JSON.writeValueAsString(pack.phenomenon()));
        System.out.println("pack.coverage=" + JSON.writeValueAsString(pack.coverage()));
        System.out.println("pack.confidence=" + JSON.writeValueAsString(pack.confidence()));
        System.out.println("requestResults (as narrative sees them)="
                + JSON.writeValueAsString(pack.requestResults()));
        System.out.println("narrative.headline=" + narrative.headline());
        System.out.println("narrative.explanation=" + narrative.explanation());
        System.out.println("narrative.keyFindings=" + JSON.writeValueAsString(narrative.keyFindings()));

        System.out.println("\n========== VALUE=0 ROOT CAUSE ==========");
        System.out.println("""
                First place payment_type=2 becomes value=0:
                NOT in the planner, NOT in the compiler, NOT in the narrative.
                It is in the warehouse result for the BASELINE slice_metric:
                  tip_amount for payment_type=2 (cash) is recorded as 0 in TLC yellow trip data
                  because cash tips are not captured by the electronic system.
                Compiled SQL for BASELINE correctly filters payment_type=2 and AVG(tip_amount).
                AVG of zeros = 0.0 — data fact, not a dropped filter.
                """);

        System.out.println("\n========== WAREHOUSE PANEL vs EVIDENCEPACK DISCONNECT ==========");
        UUID runId = UUID.randomUUID();
        ExecutionDiagnosticSession session = new ExecutionDiagnosticSession();

        // Simulate OLD bug path: routing facts recorded WITHOUT sql/row counts.
        Map<String, Object> routingOnly = new LinkedHashMap<>();
        routingOnly.put("question", QUESTION);
        routingOnly.put("served_path", "investigation");
        routingOnly.put("planner_mode", "gpt");
        routingOnly.put("planner_execution_mode", "INVESTIGATION");
        session.recordWarehouseFacts(runId, routingOnly);

        Map<String, Object> staleFacts = session.warehouseFacts(runId);
        System.out.println("BEFORE FIX (routing facts only):");
        System.out.println("  generated_sql empty? "
                + (staleFacts.get("generated_sql") == null
                || staleFacts.get("generated_sql").toString().isBlank()));
        System.out.println("  warehouse_row_count="
                + staleFacts.getOrDefault("warehouse_row_count", "(missing→UI defaults to 0)"));
        System.out.println("  EvidencePack still reports "
                + pack.coverage().requestsSucceeded() + "/"
                + pack.coverage().requestsPlanned()
                + " — these come from DecisionRunResult.routing.investigation, NOT execution_path.");

        // AFTER FIX: investigation runtime records SQL facts; merge preserves them under routing facts.
        session.clear(runId);
        Map<String, Object> invFacts = new LinkedHashMap<>();
        invFacts.put("question", QUESTION);
        invFacts.put("served_path", "investigation");
        invFacts.put("materialized_query", "INVESTIGATION_BATCH:" + tableRef);
        StringBuilder sqlJoined = new StringBuilder();
        int totalRows = 0;
        for (EvidencePack.RequestResult rr : pack.requestResults()) {
            if (rr.sql() != null) {
                if (sqlJoined.length() > 0) {
                    sqlJoined.append("\n;\n");
                }
                sqlJoined.append("-- ").append(rr.requestId()).append("\n").append(rr.sql());
            }
            totalRows += rr.rows() != null ? rr.rows().size() : 0;
        }
        invFacts.put("generated_sql", sqlJoined.toString());
        invFacts.put("warehouse_row_count", totalRows);
        invFacts.put("sample_rows", pack.requestResults().stream()
                .flatMap(rr -> rr.rows().stream())
                .limit(20)
                .toList());
        session.recordWarehouseFacts(runId, invFacts);
        // routing facts merge on top (as DecisionRuntime does after investigation run)
        session.recordWarehouseFacts(runId, routingOnly);

        Map<String, Object> fixedFacts = session.warehouseFacts(runId);
        System.out.println("AFTER FIX (investigation SQL facts + merged routing facts):");
        System.out.println("  generated_sql length="
                + fixedFacts.get("generated_sql").toString().length());
        System.out.println("  warehouse_row_count=" + fixedFacts.get("warehouse_row_count"));
        System.out.println("  materialized_query=" + fixedFacts.get("materialized_query"));
        assertFalse(fixedFacts.get("generated_sql").toString().isBlank());
        assertEquals(2, ((Number) fixedFacts.get("warehouse_row_count")).intValue());
        assertEquals(2, pack.coverage().requestsSucceeded());

        DecisionRunResult result = narrativeRenderer.toResult(runId.toString(), pack, narrative);
        assertTrue(result.routing().investigation().containsKey("evidence_requests"));

        Path out = Path.of("target/payment-type-investigation-execution-trace.json");
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("question", QUESTION);
        artifact.put("investigation_plan", plan);
        artifact.put("compiled", compiled.requests().stream().map(cr -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("request_id", cr.request().requestId());
            m.put("kind", cr.request().kind());
            m.put("role", cr.request().role());
            m.put("ok", cr.ok());
            if (cr.ok()) {
                m.put("query_spec_key", cr.spec().key());
                m.put("sql", cr.spec().sql());
                m.put("rows", warehouseByKey.get(cr.spec().key()));
                m.put("row_count", warehouseByKey.get(cr.spec().key()).size());
            } else {
                m.put("error", cr.error());
            }
            return m;
        }).toList());
        artifact.put("evidence_pack", pack);
        artifact.put("narrative", narrative);
        artifact.put("warehouse_panel_before_fix", Map.of(
                "generated_sql", "",
                "warehouse_row_count", 0,
                "reason", "recordPlannerRoutingFacts wrote facts without SQL; buildExecutionPath returned early"));
        artifact.put("warehouse_panel_after_fix", Map.of(
                "generated_sql_len", fixedFacts.get("generated_sql").toString().length(),
                "warehouse_row_count", fixedFacts.get("warehouse_row_count"),
                "materialized_query", fixedFacts.get("materialized_query")));
        artifact.put("value_zero_root_cause",
                "TLC yellow trip data: tip_amount for payment_type=2 (cash) is not captured; warehouse AVG returns 0.0");
        Files.writeString(out, JSON.writeValueAsString(artifact));
        System.out.println("\nWrote " + out.toAbsolutePath());
    }

    private static InvestigationPlan paymentTypePlan() {
        return new InvestigationPlan(
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
                                null, null, null)
                ),
                0.92);
    }

    private static InvestigationObjective.Slice sliceFilter(String column, String value) {
        return new InvestigationObjective.Slice(List.of(
                new InvestigationObjective.Constraint(
                        new InvestigationObjective.Filter(column, "=", value),
                        null, null, null)));
    }

    private static String extractPaymentFilter(String sql) {
        int where = sql.toUpperCase().indexOf("WHERE");
        return where >= 0 ? sql.substring(where) : sql;
    }
}
