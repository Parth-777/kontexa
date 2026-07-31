package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot.CatalogueColumn;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredSemanticPlanner;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Diagnostic probe: traces measure/dimension binding for GA nested questions.
 * Does not call GPT — simulates planner JSON shapes that reproduce reported failures.
 */
class Phase2MeasureBindingDiagnosticProbeTest {

    private static final String QUESTION =
            "Which geoNetwork.country generates the most sessions?";

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("Trace InvestigationPlan + binding for geoNetwork.country sessions question")
    void traceGeoNetworkCountrySessionsBinding() throws Exception {
        ApprovedCatalogueSnapshot nestedCatalogue = gaNestedCatalogue();
        ApprovedCatalogueSnapshot flatLegacyCatalogue = gaFlatLegacyCatalogue();

        // Simulated GPT payload matching reported failure pattern:
        // - explicit nested dimension in investigation_plan
        // - business word "sessions" as metric (not catalogue column visitId)
        // - top-level metric null after bindColumn
        String simulatedGptJson = """
                {
                  "execution_mode": "INVESTIGATION",
                  "intent": "RANKING",
                  "metric": "sessions",
                  "dimensions": ["geoNetwork.country"],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": {"column": "sessions", "direction": "DESC"},
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.88,
                  "reasoning": "Rank countries by session count",
                  "investigation_plan": {
                    "phenomenon": {
                      "subject": "countries",
                      "measure": {"column": "sessions", "aggregation": "COUNT", "grain": []}
                    },
                    "scope": {"filters": []},
                    "evidence_requests": [
                      {
                        "id": "rank_countries",
                        "kind": "top_n",
                        "role": null,
                        "measure": {"column": "sessions", "aggregation": "COUNT", "grain": []},
                        "dimension": "geoNetwork.country",
                        "limit": 10
                      }
                    ],
                    "semantic_confidence": 0.88
                  },
                  "investigation_objective": null,
                  "alternatives": []
                }
                """;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("question", QUESTION);

        for (String catalogueLabel : List.of("nested_phase_b", "flat_legacy")) {
            ApprovedCatalogueSnapshot catalogue =
                    "nested_phase_b".equals(catalogueLabel) ? nestedCatalogue : flatLegacyCatalogue;
            report.put(catalogueLabel + ".metric_candidates", catalogue.metricColumns());
            report.put(catalogueLabel + ".dimension_candidates", catalogue.dimensionColumns());
            report.put(catalogueLabel + ".bindable_leaves", catalogue.bindableLeafColumns());

            StructuredSemanticPlan parsed = parsePlanWithBinding(
                    json.readTree(simulatedGptJson), catalogue);
            report.put(catalogueLabel + ".bound_top_level_metric", parsed.metric());
            report.put(catalogueLabel + ".bound_top_level_dimensions", parsed.dimensions());

            GptPlanningOutcome outcome = new GptPlanningOutcome(
                    parsed, null, null, List.of(), null, null, StructuredSemanticPlan.MODE_INVESTIGATION);

            InvestigationPlan plan = new InvestigationPlanMapper(
                    new InvestigationPlanParser(),
                    new InvestigationObjectiveParser(),
                    new LegacyObjectiveAdapter()).map(outcome);

            assertNotNull(plan);
            report.put(catalogueLabel + ".resolved_dimension", primaryDimension(plan));
            report.put(catalogueLabel + ".resolved_measure", primaryMetric(plan));
            report.put(catalogueLabel + ".investigation_plan", plan);

            EvidenceRequestSqlCompiler compiler =
                    new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());
            var compiled = compiler.compile(plan, catalogue.tableRef());
            List<Map<String, Object>> compileResults = compiled.requests().stream().map(cr -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("request_id", cr.request().requestId());
                row.put("kind", cr.request().kind());
                row.put("dimension", cr.request().dimension());
                row.put("measure_column",
                        cr.request().measure() != null ? cr.request().measure().column() : null);
                row.put("ok", cr.ok());
                row.put("error", cr.error());
                return row;
            }).toList();
            report.put(catalogueLabel + ".compile_results", compileResults);

            report.put(catalogueLabel + ".catalogue_metric_semantics_denominator_for_sessions",
                    String.valueOf(inferDenominator(catalogue, "sessions")));
            report.put(catalogueLabel + ".catalogue_metric_semantics_denominator_for_visitId",
                    String.valueOf(inferDenominator(catalogue, "visitId")));
        }

        System.out.println("=== PHASE2 MEASURE BINDING DIAGNOSTIC ===");
        System.out.println(json.writeValueAsString(report));
    }

    @Test
    @DisplayName("When GPT omits measure / uses null measure — identify exact loss point")
    void traceNullMeasurePath() throws Exception {
        ApprovedCatalogueSnapshot catalogue = gaNestedCatalogue();

        // Case A: measure object present but column is a non-catalogue word (sessions)
        // Case B: measure is null inside evidence_requests (GPT omitted it, relying on top-level metric)
        String gptOmitMeasure = """
                {
                  "execution_mode": "INVESTIGATION",
                  "intent": "RANKING",
                  "metric": "sessions",
                  "dimensions": ["geoNetwork.country"],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": null,
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.9,
                  "reasoning": "rank by country",
                  "investigation_plan": {
                    "phenomenon": {
                      "subject": "countries",
                      "measure": null
                    },
                    "scope": {"filters": []},
                    "evidence_requests": [
                      {
                        "id": "1",
                        "kind": "metric_by_dimension",
                        "role": null,
                        "measure": null,
                        "dimension": "geoNetwork.country"
                      }
                    ],
                    "semantic_confidence": 0.9
                  },
                  "investigation_objective": null,
                  "alternatives": []
                }
                """;

        StructuredSemanticPlan parsed = parsePlanWithBinding(json.readTree(gptOmitMeasure), catalogue);
        InvestigationPlan plan = new InvestigationPlanMapper(
                new InvestigationPlanParser(),
                new InvestigationObjectiveParser(),
                new LegacyObjectiveAdapter()).map(
                new GptPlanningOutcome(
                        parsed, null, null, List.of(), null, null,
                        StructuredSemanticPlan.MODE_INVESTIGATION));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("top_level_metric_after_bindColumn", parsed.metric());
        report.put("top_level_dimensions_after_bindColumn", parsed.dimensions());
        report.put("has_investigation_plan_node", parsed.investigationPlan() != null);
        report.put("raw_investigation_plan_measure",
                parsed.investigationPlan() != null
                        ? parsed.investigationPlan().path("phenomenon").path("measure")
                        : null);
        report.put("mapped_plan_phenomenon_measure",
                plan != null && plan.phenomenon() != null ? plan.phenomenon().measure() : "PLAN_NULL");
        report.put("mapped_plan_request_measure",
                plan != null && !plan.evidenceRequests().isEmpty()
                        ? plan.evidenceRequests().getFirst().measure() : "NO_REQUESTS");
        report.put("mapped_plan_request_dimension",
                plan != null && !plan.evidenceRequests().isEmpty()
                        ? plan.evidenceRequests().getFirst().dimension() : null);

        EvidenceRequestSqlCompiler.CompiledProgram compiled =
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder())
                        .compile(plan, catalogue.tableRef());
        report.put("compile_ok", compiled.requests().getFirst().ok());
        report.put("compile_error", compiled.requests().getFirst().error());

        // Contrast: top-level metric correctly set to visitId but investigation_plan measure still null
        String gptTopLevelVisitIdButNullPlanMeasure = """
                {
                  "execution_mode": "INVESTIGATION",
                  "intent": "RANKING",
                  "metric": "visitId",
                  "dimensions": ["geoNetwork.country"],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": null,
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.9,
                  "reasoning": "rank",
                  "investigation_plan": {
                    "phenomenon": { "subject": "countries", "measure": null },
                    "scope": {"filters": []},
                    "evidence_requests": [
                      {
                        "id": "1",
                        "kind": "metric_by_dimension",
                        "role": null,
                        "measure": null,
                        "dimension": "geoNetwork.country"
                      }
                    ],
                    "semantic_confidence": 0.9
                  },
                  "investigation_objective": null,
                  "alternatives": []
                }
                """;
        StructuredSemanticPlan parsed2 = parsePlanWithBinding(
                json.readTree(gptTopLevelVisitIdButNullPlanMeasure), catalogue);
        InvestigationPlan plan2 = new InvestigationPlanMapper(
                new InvestigationPlanParser(),
                new InvestigationObjectiveParser(),
                new LegacyObjectiveAdapter()).map(
                new GptPlanningOutcome(
                        parsed2, null, null, List.of(), null, null,
                        StructuredSemanticPlan.MODE_INVESTIGATION));
        report.put("case_B_top_level_metric_bound", parsed2.metric());
        report.put("case_B_request_measure_still_null",
                plan2.evidenceRequests().getFirst().measure());
        report.put("case_B_compile_error",
                new EvidenceRequestSqlCompiler(new SlicePredicateBuilder())
                        .compile(plan2, catalogue.tableRef())
                        .requests().getFirst().error());

        System.out.println("=== NULL MEASURE LOSS POINT ===");
        System.out.println(json.writeValueAsString(report));
    }

    private static Object inferDenominator(ApprovedCatalogueSnapshot catalogue, String column) {
        try {
            Method m = Class.forName(
                            "com.example.BACKEND.catalogue.semantic.phase2.completion.CatalogueMetricSemantics")
                    .getDeclaredMethod(
                            "inferContributionDenominator", ApprovedCatalogueSnapshot.class, String.class);
            m.setAccessible(true);
            return m.invoke(null, catalogue, column);
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    private StructuredSemanticPlan parsePlanWithBinding(JsonNode node, ApprovedCatalogueSnapshot catalogue)
            throws Exception {
        Method parsePlan = GptStructuredSemanticPlanner.class.getDeclaredMethod(
                "parsePlan", JsonNode.class, ApprovedCatalogueSnapshot.class);
        parsePlan.setAccessible(true);
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(null, json);
        return (StructuredSemanticPlan) parsePlan.invoke(planner, node, catalogue);
    }

    private ApprovedCatalogueSnapshot gaNestedCatalogue() throws Exception {
        String snapshotJson = """
                {
                  "tables": [{
                    "tableName": "ga_sessions_20160801",
                    "tableSchema": "google_analytics_sample",
                    "columns": [
                      {"columnName":"trafficSource","role":"container","dataType":"RECORD","container":true},
                      {"columnName":"trafficSource.source","role":"dimension","dataType":"STRING","parentPath":"trafficSource"},
                      {"columnName":"trafficSource.medium","role":"dimension","dataType":"STRING","parentPath":"trafficSource"},
                      {"columnName":"geoNetwork","role":"container","dataType":"RECORD","container":true},
                      {"columnName":"geoNetwork.country","role":"dimension","dataType":"STRING","parentPath":"geoNetwork"},
                      {"columnName":"geoNetwork.city","role":"dimension","dataType":"STRING","parentPath":"geoNetwork"},
                      {"columnName":"channelGrouping","role":"dimension","dataType":"STRING"},
                      {"columnName":"visitId","role":"metric","dataType":"INTEGER","aggregationMethod":"COUNT","description":"Unique session identifier"}
                    ]
                  }]
                }
                """;
        return SemanticCatalogueFactory.catalogueFrom(
                json.readTree(snapshotJson), gaBundle("ga_sessions_20160801"));
    }

    /** Pre-Phase-B style: RECORD parents listed as dimensions, no leaf paths. */
    private ApprovedCatalogueSnapshot gaFlatLegacyCatalogue() {
        return new ApprovedCatalogueSnapshot(
                "ga_sessions_20160801",
                "google_analytics_sample.ga_sessions_20160801",
                List.of(
                        new CatalogueColumn("trafficSource", "dimension", "RECORD", "Traffic source struct",
                                null, List.of(), true, null),
                        new CatalogueColumn("geoNetwork", "dimension", "RECORD", "Geo struct",
                                null, List.of(), true, null),
                        new CatalogueColumn("channelGrouping", "dimension", "STRING", "Channel grouping",
                                null, List.of()),
                        new CatalogueColumn("visitId", "metric", "INTEGER", "Session id", "COUNT", List.of())));
    }

    private static RegistryResolutionBundle gaBundle(String tableRef) {
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor(tableRef, tableRef, List.of(), List.of())),
                List.of(new MetricDescriptor("visitId", tableRef + ".visitId", "INTEGER", "COUNT", null)),
                List.of(
                        new DimensionDescriptor("geoNetwork.country", tableRef + ".geoNetwork.country", "CATEGORICAL"),
                        new DimensionDescriptor("trafficSource.source", tableRef + ".trafficSource.source", "CATEGORICAL"),
                        new DimensionDescriptor("channelGrouping", tableRef + ".channelGrouping", "CATEGORICAL")),
                new ObjectiveDescriptor("RANKING", "RANKING", List.of()));
    }

    private static String primaryDimension(InvestigationPlan plan) {
        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            if (req.dimension() != null && !req.dimension().isBlank()) {
                return req.dimension();
            }
        }
        return null;
    }

    private static String primaryMetric(InvestigationPlan plan) {
        if (plan.phenomenon() != null && plan.phenomenon().measure() != null
                && plan.phenomenon().measure().column() != null) {
            return plan.phenomenon().measure().aggregation() + "("
                    + plan.phenomenon().measure().column() + ")";
        }
        for (InvestigationPlan.EvidenceRequest req : plan.evidenceRequests()) {
            if (req.measure() != null && req.measure().column() != null) {
                return req.measure().aggregation() + "(" + req.measure().column() + ")";
            }
        }
        return null;
    }
}
