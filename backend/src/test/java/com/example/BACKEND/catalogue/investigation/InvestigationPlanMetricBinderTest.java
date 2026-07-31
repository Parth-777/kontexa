package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredSemanticPlanner;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationPlanMetricBinderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final InvestigationPlanMapper planMapper = new InvestigationPlanMapper(
            new InvestigationPlanParser(),
            new InvestigationObjectiveParser(),
            new LegacyObjectiveAdapter());
    private final EvidenceRequestSqlCompiler compiler =
            new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());

    @Test
    @DisplayName("bound top-level visitId fills null evidenceRequest measure as COUNT(visitId)")
    void boundVisitIdFillsNullEvidenceMeasure() throws Exception {
        StructuredSemanticPlan semantic = semanticPlan(
                "visitId",
                "COUNT",
                investigationPlanJson(
                        null, "COUNT",
                        "geoNetwork.country", "metric_by_dimension"));

        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertNotNull(plan);
        assertEquals("visitId", plan.phenomenon().measure().column());
        assertEquals("COUNT", plan.phenomenon().measure().aggregation());
        assertEquals(1, plan.evidenceRequests().size());
        assertEquals("visitId", plan.evidenceRequests().getFirst().measure().column());
        assertEquals("COUNT", plan.evidenceRequests().getFirst().measure().aggregation());
        assertEquals("geoNetwork.country", plan.evidenceRequests().getFirst().dimension());

        assertCompilerReceivesMeasureColumn(plan);
    }

    @Test
    @DisplayName("bound top-level totals.visits fills null evidenceRequest measure as SUM(totals.visits)")
    void boundTotalsVisitsFillsNullEvidenceMeasure() throws Exception {
        StructuredSemanticPlan semantic = semanticPlan(
                "totals.visits",
                "SUM",
                investigationPlanJson(
                        null, "SUM",
                        "geoNetwork.country", "top_n"));

        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertNotNull(plan);
        assertEquals("totals.visits", plan.phenomenon().measure().column());
        assertEquals("SUM", plan.phenomenon().measure().aggregation());
        assertEquals("totals.visits", plan.evidenceRequests().getFirst().measure().column());
        assertEquals("SUM", plan.evidenceRequests().getFirst().measure().aggregation());

        assertCompilerReceivesMeasureColumn(plan);
    }

    @Test
    @DisplayName("null investigation-plan measure is filled from bound top-level metric")
    void nullMeasureFilledFromTopLevel() throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "countries");
        phenomenon.putNull("measure");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        ObjectNode req = requests.addObject();
        req.put("id", "1");
        req.put("kind", "metric_by_dimension");
        req.putNull("measure");
        req.put("dimension", "geoNetwork.country");
        inv.put("semantic_confidence", 0.9);

        StructuredSemanticPlan semantic = semanticPlan("visitId", "COUNT", inv);
        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertNotNull(plan.evidenceRequests().getFirst().measure());
        assertEquals("visitId", plan.evidenceRequests().getFirst().measure().column());
        assertCompilerReceivesMeasureColumn(plan);
    }

    @Test
    @DisplayName("bound metric fills null measures across measure-requiring requests; leaves distribution null")
    void boundMetricPropagatesToAllRequests() throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "countries");
        phenomenon.putNull("measure");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        requests.add(requestNode("rank", "top_n", null, "geoNetwork.country"));
        requests.add(requestNode("dist", "distribution", null, "geoNetwork.country"));
        requests.add(requestNode("by_dim", "metric_by_dimension", null, "channelGrouping"));
        inv.put("semantic_confidence", 0.9);

        StructuredSemanticPlan semantic = semanticPlan("visitId", "COUNT", inv);
        InvestigationPlan plan = planMapper.map(outcome(semantic));

        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            if ("distribution".equals(req.kind())) {
                assertNull(req.measure(), "distribution must not invent a measure");
            } else {
                assertEquals("visitId", req.measure().column());
                assertEquals("COUNT", req.measure().aggregation());
            }
        }
        assertCompilerReceivesMeasureColumn(plan);
    }

    @Test
    @DisplayName("explicit non-null investigation_plan measure is not overwritten by top-level metric")
    void explicitRequestMeasurePreserved() throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "fees");
        phenomenon.putNull("measure");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        requests.add(requestNode("1", "metric_by_dimension", "total_amount", "payment_type"));
        inv.put("semantic_confidence", 0.9);

        StructuredSemanticPlan semantic = semanticPlan("airport_fee", "SUM", inv);
        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertEquals("total_amount", plan.evidenceRequests().getFirst().measure().column());
    }

    @Test
    @DisplayName("top-level bound dimension propagates into EvidenceRequests when plan dimension is null")
    void topLevelDimensionPropagatesWhenInvestigationDimensionNull() throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "countries");
        phenomenon.putNull("measure");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        ObjectNode req = requests.addObject();
        req.put("id", "1");
        req.put("kind", "metric_by_dimension");
        req.putNull("measure");
        req.putNull("dimension");
        inv.put("semantic_confidence", 0.9);

        StructuredSemanticPlan semantic = semanticPlan(
                "visitorId", "COUNT", List.of("geoNetwork.country"), inv);
        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertNotNull(plan);
        assertEquals("geoNetwork.country", plan.evidenceRequests().getFirst().dimension());
        assertEquals("visitorId", plan.evidenceRequests().getFirst().measure().column());
        assertCompilerReceivesNonNullDimension(plan);
    }

    @Test
    @DisplayName("geoNetwork.country survives mapper when only present on top-level dimensions")
    void geoNetworkCountrySurvivesMapperCreation() throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "countries");
        phenomenon.putNull("measure");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        ObjectNode req = requests.addObject();
        req.put("id", "rank_countries");
        req.put("kind", "top_n");
        req.putNull("measure");
        req.putNull("dimension");
        req.put("limit", 10);
        inv.put("semantic_confidence", 0.9);

        StructuredSemanticPlan semantic = semanticPlan(
                "visitorId", "COUNT", List.of("geoNetwork.country"), inv);
        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertEquals("geoNetwork.country", plan.evidenceRequests().getFirst().dimension());
        assertCompilerReceivesNonNullDimension(plan);
        String sql = compiler.compile(plan, "ga_sessions_20160801")
                .requests().getFirst().spec().sql();
        assertTrue(sql.contains("geoNetwork.country"));
    }

    @Test
    @DisplayName("explicit investigation_plan dimension is not overwritten by top-level dimensions")
    void explicitInvestigationDimensionOverridesTopLevel() throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "channels");
        phenomenon.putNull("measure");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        ObjectNode req = requests.addObject();
        req.put("id", "1");
        req.put("kind", "metric_by_dimension");
        req.putNull("measure");
        req.put("dimension", "channelGrouping");
        inv.put("semantic_confidence", 0.9);

        StructuredSemanticPlan semantic = semanticPlan(
                "visitorId", "COUNT", List.of("geoNetwork.country"), inv);
        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertEquals("channelGrouping", plan.evidenceRequests().getFirst().dimension());
    }

    @Test
    @DisplayName("NYC Taxi flat dimension remains unchanged through mapper")
    void nycTaxiFlatDimensionUnchanged() throws Exception {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "regions");
        ObjectNode measure = phenomenon.putObject("measure");
        measure.put("column", "tip_amount");
        measure.put("aggregation", "AVG");
        measure.putArray("grain");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        requests.add(requestNode("1", "metric_by_dimension", "tip_amount", "region"));
        inv.put("semantic_confidence", 0.9);

        StructuredSemanticPlan semantic = semanticPlan(
                "tip_amount", "AVG", List.of("region"), inv);
        InvestigationPlan plan = planMapper.map(outcome(semantic));

        assertEquals("region", plan.evidenceRequests().getFirst().dimension());
        assertEquals("tip_amount", plan.evidenceRequests().getFirst().measure().column());
        EvidenceRequestSqlCompiler.CompiledProgram compiled =
                compiler.compile(plan, "yellow_taxi_trips");
        assertTrue(compiled.requests().getFirst().ok());
        assertTrue(compiled.requests().getFirst().spec().sql().contains("GROUP BY region"));
    }

    @Test
    @DisplayName("Defect 1 only: GPT emits visitId + null request measure → propagation alone makes compile succeed")
    void defect1_visitIdDirectly_propagationAloneSucceeds() throws Exception {
        ApprovedCatalogueSnapshot catalogue = gaCatalogue();
        JsonNode gpt = rankingPlanJson("visitId", "COUNT", null, "geoNetwork.country");

        StructuredSemanticPlan afterBindColumn = parsePlanWithBinding(gpt, catalogue);
        assertEquals("visitId", afterBindColumn.metric(),
                "bindColumn must keep catalogue metric visitId");
        assertTrue(afterBindColumn.investigationPlan()
                .path("evidence_requests").get(0).path("measure").isNull());

        InvestigationPlan plan = planMapper.map(outcome(afterBindColumn));
        assertNotNull(plan.evidenceRequests().getFirst().measure());
        assertEquals("visitId", plan.evidenceRequests().getFirst().measure().column());
        assertEquals("COUNT", plan.evidenceRequests().getFirst().measure().aggregation());

        EvidenceRequestSqlCompiler.CompiledRequest compiled =
                compiler.compile(plan, catalogue.tableRef()).requests().getFirst();
        assertTrue(compiled.ok(), () -> "compile failed: " + compiled.error());
        assertFalse(compiled.error() != null
                && compiled.error().contains("measure.column is required"));
    }

    @Test
    @DisplayName("Defect 2 only: GPT emits sessions + null request measure → bindColumn nulls metric; propagation cannot help")
    void defect2_sessionsUnbound_propagationAloneDoesNotSucceed() throws Exception {
        ApprovedCatalogueSnapshot catalogue = gaCatalogue();
        JsonNode gpt = rankingPlanJson("sessions", "COUNT", null, "geoNetwork.country");

        StructuredSemanticPlan afterBindColumn = parsePlanWithBinding(gpt, catalogue);
        assertNull(afterBindColumn.metric(),
                "bindColumn must drop non-catalogue synonym 'sessions'");
        assertTrue(afterBindColumn.investigationPlan()
                .path("evidence_requests").get(0).path("measure").isNull());

        InvestigationPlan plan = planMapper.map(outcome(afterBindColumn));
        assertNull(plan.evidenceRequests().getFirst().measure(),
                "binder must no-op when top-level metric is null");

        EvidenceRequestSqlCompiler.CompiledRequest compiled =
                compiler.compile(plan, catalogue.tableRef()).requests().getFirst();
        assertFalse(compiled.ok());
        assertTrue(compiled.error().contains("measure.column is required"));
    }

    @Test
    @DisplayName("Defects are independent: visitId succeeds; sessions fails on same catalogue and mapper")
    void twoDefectsIndependentOnSamePipeline() throws Exception {
        ApprovedCatalogueSnapshot catalogue = gaCatalogue();

        InvestigationPlan visitIdPlan = planMapper.map(outcome(
                parsePlanWithBinding(rankingPlanJson("visitId", "COUNT", null, "browser"), catalogue)));
        InvestigationPlan sessionsPlan = planMapper.map(outcome(
                parsePlanWithBinding(rankingPlanJson("sessions", "COUNT", null, "browser"), catalogue)));

        assertEquals("visitId", visitIdPlan.evidenceRequests().getFirst().measure().column());
        assertNull(sessionsPlan.evidenceRequests().getFirst().measure());

        assertTrue(compiler.compile(visitIdPlan, catalogue.tableRef()).requests().getFirst().ok());
        assertFalse(compiler.compile(sessionsPlan, catalogue.tableRef()).requests().getFirst().ok());
    }

    private void assertCompilerReceivesNonNullDimension(InvestigationPlan plan) {
        EvidenceRequestSqlCompiler.CompiledProgram compiled =
                compiler.compile(plan, "ga_sessions_20160801");
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            if (!KINDS_WITH_DIMENSION.contains(cr.request().kind())) {
                continue;
            }
            assertTrue(cr.ok(), () -> cr.request().kind() + " failed: " + cr.error());
            assertNotNull(cr.request().dimension());
            assertTrue(!cr.request().dimension().isBlank());
        }
    }

    private static final java.util.Set<String> KINDS_WITH_DIMENSION = java.util.Set.of(
            "metric_by_dimension", "distribution", "rank", "top_n", "bottom_n",
            "share", "concentration", "correlation");

    private void assertCompilerReceivesMeasureColumn(InvestigationPlan plan) {
        EvidenceRequestSqlCompiler.CompiledProgram compiled =
                compiler.compile(plan, "ga_sessions_20160801");
        boolean anyMetricKind = false;
        for (EvidenceRequestSqlCompiler.CompiledRequest cr : compiled.requests()) {
            String kind = cr.request().kind();
            if ("distribution".equals(kind)) {
                continue;
            }
            anyMetricKind = true;
            assertTrue(cr.ok(), () -> kind + " failed: " + cr.error());
            assertNotNull(cr.request().measure());
            assertNotNull(cr.request().measure().column());
            assertTrue(!cr.request().measure().column().isBlank());
            assertTrue(cr.spec().sql() != null && !cr.spec().sql().isBlank());
        }
        assertTrue(anyMetricKind);
    }

    private StructuredSemanticPlan semanticPlan(
            String boundMetric,
            String aggregation,
            ObjectNode investigationPlan
    ) {
        return semanticPlan(boundMetric, aggregation, List.of("geoNetwork.country"), investigationPlan);
    }

    private StructuredSemanticPlan semanticPlan(
            String boundMetric,
            String aggregation,
            List<String> dimensions,
            ObjectNode investigationPlan
    ) {
        return new StructuredSemanticPlan(
                "RANKING",
                boundMetric,
                null,
                dimensions,
                List.of(),
                new StructuredSemanticPlan.SemanticAggregations(aggregation, null),
                null,
                10,
                null,
                null,
                0.9,
                "test",
                List.of(),
                StructuredSemanticPlan.MODE_INVESTIGATION,
                null,
                null,
                investigationPlan);
    }

    private ObjectNode investigationPlanJson(
            String measureColumn,
            String aggregation,
            String dimension,
            String kind
    ) {
        ObjectNode inv = mapper.createObjectNode();
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "countries");
        if (measureColumn == null) {
            phenomenon.putNull("measure");
        } else {
            ObjectNode measure = phenomenon.putObject("measure");
            measure.put("column", measureColumn);
            measure.put("aggregation", aggregation);
            measure.putArray("grain");
        }
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        requests.add(requestNode("1", kind, measureColumn, dimension));
        inv.put("semantic_confidence", 0.9);
        return inv;
    }

    private ObjectNode requestNode(String id, String kind, String measureColumn, String dimension) {
        ObjectNode req = mapper.createObjectNode();
        req.put("id", id);
        req.put("kind", kind);
        if (measureColumn == null) {
            req.putNull("measure");
        } else {
            ObjectNode measure = req.putObject("measure");
            measure.put("column", measureColumn);
            measure.put("aggregation", "COUNT");
            measure.putArray("grain");
        }
        if (dimension == null) {
            req.putNull("dimension");
        } else {
            req.put("dimension", dimension);
        }
        req.put("limit", 10);
        return req;
    }

    private static GptPlanningOutcome outcome(StructuredSemanticPlan plan) {
        return new GptPlanningOutcome(
                plan, null, null, List.of(), null, null, StructuredSemanticPlan.MODE_INVESTIGATION);
    }

    private ObjectNode rankingPlanJson(
            String topLevelMetric,
            String aggregation,
            String requestMeasureColumn,
            String dimension
    ) {
        ObjectNode root = mapper.createObjectNode();
        root.put("execution_mode", "INVESTIGATION");
        root.put("intent", "RANKING");
        root.put("metric", topLevelMetric);
        root.putNull("secondary_metric");
        root.putArray("dimensions").add(dimension);
        root.putArray("filters");
        ObjectNode aggs = root.putObject("aggregations");
        aggs.put("primary", aggregation);
        aggs.putNull("secondary");
        root.putNull("ordering");
        root.put("limit", 10);
        root.putNull("relationship_variable");
        root.putNull("time_grain");
        root.put("confidence", 0.9);
        root.put("reasoning", "independence probe");
        root.putNull("investigation_objective");
        root.putArray("alternatives");

        ObjectNode inv = root.putObject("investigation_plan");
        ObjectNode phenomenon = inv.putObject("phenomenon");
        phenomenon.put("subject", "members");
        phenomenon.putNull("measure");
        inv.putObject("scope").putArray("filters");
        ArrayNode requests = inv.putArray("evidence_requests");
        requests.add(requestNode("1", "metric_by_dimension", requestMeasureColumn, dimension));
        inv.put("semantic_confidence", 0.9);
        return root;
    }

    private StructuredSemanticPlan parsePlanWithBinding(JsonNode node, ApprovedCatalogueSnapshot catalogue)
            throws Exception {
        Method parsePlan = GptStructuredSemanticPlanner.class.getDeclaredMethod(
                "parsePlan", JsonNode.class, ApprovedCatalogueSnapshot.class);
        parsePlan.setAccessible(true);
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(null, mapper);
        return (StructuredSemanticPlan) parsePlan.invoke(planner, node, catalogue);
    }

    private ApprovedCatalogueSnapshot gaCatalogue() throws Exception {
        String snapshotJson = """
                {
                  "tables": [{
                    "tableName": "ga_sessions_20160801",
                    "tableSchema": "google_analytics_sample",
                    "columns": [
                      {"columnName":"browser","role":"dimension","dataType":"STRING"},
                      {"columnName":"geoNetwork.country","role":"dimension","dataType":"STRING","parentPath":"geoNetwork"},
                      {"columnName":"visitId","role":"metric","dataType":"INTEGER","aggregationMethod":"COUNT"}
                    ]
                  }]
                }
                """;
        RegistryResolutionBundle bundle = new RegistryResolutionBundle(
                List.of(new EntityDescriptor(
                        "ga_sessions_20160801", "ga_sessions_20160801", List.of(), List.of())),
                List.of(new MetricDescriptor(
                        "visitId", "ga_sessions_20160801.visitId", "INTEGER", "COUNT", null)),
                List.of(
                        new DimensionDescriptor(
                                "browser", "ga_sessions_20160801.browser", "CATEGORICAL"),
                        new DimensionDescriptor(
                                "geoNetwork.country",
                                "ga_sessions_20160801.geoNetwork.country",
                                "CATEGORICAL")),
                new ObjectiveDescriptor("RANKING", "RANKING", List.of()));
        return SemanticCatalogueFactory.catalogueFrom(mapper.readTree(snapshotJson), bundle);
    }
}
