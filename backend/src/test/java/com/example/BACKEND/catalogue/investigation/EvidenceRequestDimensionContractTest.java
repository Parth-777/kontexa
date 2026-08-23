package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for Root Cause C: dimension-requiring kinds must not reach
 * {@code requireDimension} with a null dimension when GPT omitted the field.
 */
class EvidenceRequestDimensionContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final InvestigationPlanMapper planMapper = new InvestigationPlanMapper(
            new InvestigationPlanParser(),
            new InvestigationObjectiveParser(),
            new LegacyObjectiveAdapter());
    private final EvidenceRequestSqlCompiler compiler =
            new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());

    @Test
    @DisplayName("TEST 1: metric_by_dimension with valid dimension compiles unchanged")
    void test1_validDimensionCompiles() throws Exception {
        InvestigationPlan plan = mapPlan(
                List.of("geoNetwork.country"),
                request("ER1", "metric_by_dimension", "totals.bounces", "geoNetwork.country"));

        assertEquals("geoNetwork.country", plan.evidenceRequests().getFirst().dimension());
        var gate = EvidenceRequestDimensionContract.evaluate(plan);
        assertTrue(gate.hasExecutable());
        assertFalse(gate.hasRejectedMissingDimension());

        var compiled = compiler.compile(
                EvidenceRequestDimensionContract.planForCompilation(plan, gate), "t");
        assertEquals(1, compiled.executableSpecs().size());
        assertTrue(compiled.requests().getFirst().ok());
        assertFalse(compiled.requests().getFirst().error() != null
                && compiled.requests().getFirst().error().contains("dimension/column is required"));
    }

    @Test
    @DisplayName("TEST 2: null evidence dimension + bound top-level dimension → binder fills; executable")
    void test2_binderPropagatesTopLevelDimension() throws Exception {
        InvestigationPlan plan = mapPlan(
                List.of("geoNetwork.country"),
                request("ER1", "metric_by_dimension", "totals.bounces", null));

        assertEquals("geoNetwork.country", plan.evidenceRequests().getFirst().dimension());
        var gate = EvidenceRequestDimensionContract.evaluate(plan);
        assertTrue(gate.hasExecutable());
        assertFalse(gate.hasRejectedMissingDimension());
        assertEquals(1, compiler.compile(
                EvidenceRequestDimensionContract.planForCompilation(plan, gate), "t")
                .executableSpecs().size());
    }

    @Test
    @DisplayName("TEST 3: null evidence dimension + empty top-level dims → rejected before requireDimension")
    void test3_missingDimensionRejectedBeforeCompiler() throws Exception {
        InvestigationPlan plan = mapPlan(
                List.of(),
                request("ER1", "metric_by_dimension", "totals.bounces", null));

        assertTrue(plan.evidenceRequests().getFirst().dimension() == null
                || plan.evidenceRequests().getFirst().dimension().isBlank());

        var gate = EvidenceRequestDimensionContract.evaluate(plan);
        assertFalse(gate.hasExecutable());
        assertTrue(gate.hasRejectedMissingDimension());
        assertTrue(gate.allRequestsRejectedForMissingDimension(plan));
        assertEquals(
                EvidenceRequestDimensionContract.MISSING_DIMENSION_REASON,
                gate.rejected().getFirst().reason());

        InvestigationPlan compilePlan =
                EvidenceRequestDimensionContract.planForCompilation(plan, gate);
        var compiled = compiler.compile(compilePlan, "t");
        assertEquals(0, compiled.requests().size());
        assertEquals(0, compiled.executableSpecs().size());
        // requireDimension never invoked — no compile error containing that message
        assertTrue(compiled.requests().stream().noneMatch(cr ->
                cr.error() != null && cr.error().contains("dimension/column is required")));
    }

    @Test
    @DisplayName("TEST 4: time_series with null dimension remains executable")
    void test4_timeSeriesNullDimensionOk() throws Exception {
        ObjectNode req = request("ER1", "time_series", "totals.bounces", null);
        req.put("time_column", "visitStartTime");
        req.put("time_grain", "DAY");
        InvestigationPlan plan = mapPlan(List.of(), req);

        var gate = EvidenceRequestDimensionContract.evaluate(plan);
        assertTrue(gate.hasExecutable());
        assertFalse(gate.hasRejectedMissingDimension());
        var compiled = compiler.compile(
                EvidenceRequestDimensionContract.planForCompilation(plan, gate), "t");
        assertEquals(1, compiled.executableSpecs().size(),
                () -> compiled.requests().isEmpty() ? "no compiled requests"
                        : compiled.requests().getFirst().error());
        assertTrue(compiled.requests().getFirst().ok(), compiled.requests().getFirst().error());
    }

    @Test
    @DisplayName("TEST 5: mixed — valid request compiles; missing-dimension request rejected only")
    void test5_mixedValidAndMissing() throws Exception {
        InvestigationPlan plan = mapPlan(
                List.of(),
                request("OK1", "metric_by_dimension", "totals.bounces", "trafficSource.source"),
                request("BAD1", "metric_by_dimension", "totals.pageviews", null));

        var gate = EvidenceRequestDimensionContract.evaluate(plan);
        assertEquals(1, gate.executableRequests().size());
        assertEquals(1, gate.rejected().size());
        assertEquals("OK1", gate.executableRequests().getFirst().requestId());
        assertEquals("BAD1", gate.rejected().getFirst().request().requestId());
        assertFalse(gate.allRequestsRejectedForMissingDimension(plan));

        var compiled = compiler.compile(
                EvidenceRequestDimensionContract.planForCompilation(plan, gate), "t");
        assertEquals(1, compiled.executableSpecs().size());
        assertTrue(compiled.requests().getFirst().ok());
        assertEquals(
                EvidenceRequestDimensionContract.MISSING_DIMENSION_REASON,
                gate.rejected().getFirst().reason());
        assertFalse(gate.rejected().getFirst().reason().contains("dimension/column is required"));
    }

    @Test
    @DisplayName("TEST 6: all dimension-requiring requests missing dims → planning insufficiency path")
    void test6_allMissingExplicitPlanningReason() throws Exception {
        InvestigationPlan plan = mapPlan(
                List.of(),
                request("ER1", "metric_by_dimension", "totals.bounces", null),
                request("ER2", "rank", "totals.pageviews", null));

        var gate = EvidenceRequestDimensionContract.evaluate(plan);
        assertTrue(gate.allRequestsRejectedForMissingDimension(plan));
        assertEquals(0, gate.executableRequests().size());
        assertEquals(2, gate.rejected().size());
        for (var r : gate.rejected()) {
            assertEquals(EvidenceRequestDimensionContract.MISSING_DIMENSION_REASON, r.reason());
        }
        assertEquals(0, compiler.compile(
                EvidenceRequestDimensionContract.planForCompilation(plan, gate), "t")
                .executableSpecs().size());
    }

    @Test
    @DisplayName("TEST 7: known-good multi-kind plan with dimensions unchanged")
    void test7_knownGoodPlanUnchanged() throws Exception {
        InvestigationPlan plan = mapPlan(
                List.of("device.browser"),
                request("ER1", "metric_by_dimension", "totals.bounces", "device.browser"),
                request("ER2", "distribution", null, "device.deviceCategory"),
                request("ER3", "slice_metric", "totals.bounces", null));

        var gate = EvidenceRequestDimensionContract.evaluate(plan);
        assertEquals(3, gate.executableRequests().size());
        assertFalse(gate.hasRejectedMissingDimension());
        var compiled = compiler.compile(
                EvidenceRequestDimensionContract.planForCompilation(plan, gate), "t");
        assertEquals(3, compiled.requests().size());
        assertEquals(3, compiled.executableSpecs().size());
        assertTrue(compiled.requests().stream().allMatch(EvidenceRequestSqlCompiler.CompiledRequest::ok));
    }

    private InvestigationPlan mapPlan(List<String> topLevelDims, ObjectNode... requests)
            throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "bounce rate");
        ObjectNode measure = phenomenon.putObject("measure");
        measure.put("column", "totals.bounces");
        measure.put("aggregation", "SUM");
        measure.putArray("grain").add("session");
        inv.putObject("scope").putArray("filters");
        ArrayNode arr = inv.putArray("evidence_requests");
        for (ObjectNode r : requests) {
            arr.add(r);
        }
        inv.put("semantic_confidence", 0.9);

        ObjectNode root = mapper.createObjectNode();
        root.put("execution_mode", StructuredSemanticPlan.MODE_INVESTIGATION);
        root.put("intent", "COMPARISON");
        root.put("metric", "totals.bounces");
        root.putNull("secondary_metric");
        ArrayNode dims = root.putArray("dimensions");
        for (String d : topLevelDims) {
            dims.add(d);
        }
        root.putArray("filters");
        ObjectNode aggs = root.putObject("aggregations");
        aggs.put("primary", "SUM");
        aggs.putNull("secondary");
        root.putNull("ordering");
        root.putNull("limit");
        root.putNull("relationship_variable");
        root.putNull("time_grain");
        root.put("confidence", 0.9);
        root.put("reasoning", "test");
        root.set("investigation_plan", inv);
        root.putNull("investigation_objective");
        root.putArray("alternatives");

        // Bind top-level dims as StructuredSemanticPlan already-bound list (mapper/binder path)
        StructuredSemanticPlan semantic = new StructuredSemanticPlan(
                "COMPARISON",
                "totals.bounces",
                null,
                topLevelDims,
                List.of(),
                new StructuredSemanticPlan.SemanticAggregations("SUM", null),
                null,
                null,
                null,
                null,
                0.9,
                "test",
                List.of(),
                StructuredSemanticPlan.MODE_INVESTIGATION,
                null,
                null,
                inv);

        return planMapper.map(new GptPlanningOutcome(
                semantic, null, null, List.of(), null, null,
                StructuredSemanticPlan.MODE_INVESTIGATION));
    }

    private ObjectNode request(String id, String kind, String measureCol, String dimension) {
        return requestWithParams(id, kind, measureCol, dimension, null);
    }

    private ObjectNode requestWithParams(
            String id, String kind, String measureCol, String dimension, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("id", id);
        req.put("kind", kind);
        req.putNull("role");
        if (measureCol == null || measureCol.isBlank()) {
            req.putNull("measure");
        } else {
            ObjectNode m = req.putObject("measure");
            m.put("column", measureCol);
            m.put("aggregation", "SUM");
            m.putArray("grain");
        }
        if (dimension == null) {
            req.putNull("dimension");
        } else {
            req.put("dimension", dimension);
        }
        if (params != null) {
            req.set("params", params);
        } else {
            req.putNull("params");
        }
        return req;
    }
}
