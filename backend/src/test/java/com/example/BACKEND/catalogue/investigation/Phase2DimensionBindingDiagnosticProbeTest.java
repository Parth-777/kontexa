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
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanToAnalysisPlanAdapter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanValidationResult;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic only — traces where geoNetwork.country is dropped vs metric propagation.
 */
class Phase2DimensionBindingDiagnosticProbeTest {

    private static final String QUESTION =
            "Which geoNetwork.country generates the most sessions?";

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final InvestigationPlanMapper planMapper = new InvestigationPlanMapper(
            new InvestigationPlanParser(),
            new InvestigationObjectiveParser(),
            new LegacyObjectiveAdapter());
    private final EvidenceRequestSqlCompiler compiler =
            new EvidenceRequestSqlCompiler(new SlicePredicateBuilder());
    private final SemanticPlanToAnalysisPlanAdapter analysisAdapter =
            new SemanticPlanToAnalysisPlanAdapter();
    private final CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();

    @Test
    @DisplayName("Trace dimension path — parallel to metric binding gap")
    void traceDimensionBindingGap() throws Exception {
        ApprovedCatalogueSnapshot catalogue = gaNestedCatalogue();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("question", QUESTION);
        report.put("dimension_candidates", catalogue.dimensionColumns());
        report.put("metric_candidates", catalogue.metricColumns());
        report.put("has_geoNetwork.country", catalogue.hasColumn("geoNetwork.country"));
        report.put("is_bindable_leaf_geoNetwork.country", catalogue.isBindableLeaf("geoNetwork.country"));

        // Case A: GPT puts leaf in top-level dimensions AND in investigation_plan.dimension
        report.put("case_A", runCase(catalogue, """
                {
                  "execution_mode": "INVESTIGATION",
                  "intent": "RANKING",
                  "metric": "visitorId",
                  "dimensions": ["geoNetwork.country"],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": null,
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.9,
                  "reasoning": "rank countries",
                  "investigation_plan": {
                    "phenomenon": {
                      "subject": "countries",
                      "measure": {"column": "sessions", "aggregation": "COUNT", "grain": []}
                    },
                    "scope": {"filters": []},
                    "evidence_requests": [{
                      "id": "1",
                      "kind": "metric_by_dimension",
                      "role": null,
                      "measure": null,
                      "dimension": "geoNetwork.country"
                    }],
                    "semantic_confidence": 0.9
                  },
                  "investigation_objective": null,
                  "alternatives": []
                }
                """));

        // Case B: METRIC-GAP PARALLEL — top-level dimensions bound; investigation_plan.dimension is null
        report.put("case_B_null_investigation_dimension", runCase(catalogue, """
                {
                  "execution_mode": "INVESTIGATION",
                  "intent": "RANKING",
                  "metric": "visitorId",
                  "dimensions": ["geoNetwork.country"],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": null,
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.9,
                  "reasoning": "rank countries; dim only at top-level",
                  "investigation_plan": {
                    "phenomenon": {
                      "subject": "countries",
                      "measure": null
                    },
                    "scope": {"filters": []},
                    "evidence_requests": [{
                      "id": "1",
                      "kind": "metric_by_dimension",
                      "role": null,
                      "measure": null,
                      "dimension": null
                    }],
                    "semantic_confidence": 0.9
                  },
                  "investigation_objective": null,
                  "alternatives": []
                }
                """));

        // Case C: GPT emits business synonym at top-level that bindColumn drops
        report.put("case_C_unbound_top_level_countries", runCase(catalogue, """
                {
                  "execution_mode": "INVESTIGATION",
                  "intent": "RANKING",
                  "metric": "visitorId",
                  "dimensions": ["countries"],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": null,
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.9,
                  "reasoning": "countries synonym",
                  "investigation_plan": {
                    "phenomenon": {"subject": "countries", "measure": null},
                    "scope": {"filters": []},
                    "evidence_requests": [{
                      "id": "1",
                      "kind": "metric_by_dimension",
                      "measure": null,
                      "dimension": "countries"
                    }],
                    "semantic_confidence": 0.9
                  },
                  "investigation_objective": null,
                  "alternatives": []
                }
                """));

        // Case D: CANONICAL mode — top-level dim dropped → AnalysisPlan blocker messaging
        report.put("case_D_canonical_empty_dims", runCase(catalogue, """
                {
                  "execution_mode": "CANONICAL",
                  "intent": "RANKING",
                  "metric": "visitorId",
                  "dimensions": [],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": null,
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.9,
                  "reasoning": "no dimension",
                  "investigation_plan": null,
                  "investigation_objective": null,
                  "alternatives": []
                }
                """));

        // Case E: top-level has parent RECORD geoNetwork (not leaf) — bindColumn drops it
        report.put("case_E_parent_record_dropped", runCase(catalogue, """
                {
                  "execution_mode": "INVESTIGATION",
                  "intent": "RANKING",
                  "metric": "visitorId",
                  "dimensions": ["geoNetwork"],
                  "filters": [],
                  "aggregations": {"primary": "COUNT", "secondary": null},
                  "ordering": null,
                  "limit": 10,
                  "relationship_variable": null,
                  "time_grain": null,
                  "confidence": 0.9,
                  "reasoning": "bound parent",
                  "investigation_plan": {
                    "phenomenon": {"subject": "geo", "measure": null},
                    "scope": {"filters": []},
                    "evidence_requests": [{
                      "id": "1",
                      "kind": "metric_by_dimension",
                      "measure": null,
                      "dimension": "geoNetwork"
                    }],
                    "semantic_confidence": 0.9
                  },
                  "investigation_objective": null,
                  "alternatives": []
                }
                """));

        System.out.println("=== PHASE2 DIMENSION BINDING DIAGNOSTIC ===");
        System.out.println(json.writeValueAsString(report));
    }

    private Map<String, Object> runCase(ApprovedCatalogueSnapshot catalogue, String gptJson)
            throws Exception {
        JsonNode raw = json.readTree(gptJson);
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("gpt_raw_dimensions", raw.path("dimensions"));
        out.put("gpt_raw_investigation_dimension",
                raw.path("investigation_plan").path("evidence_requests").path(0).path("dimension"));
        out.put("gpt_raw_metric", raw.path("metric"));

        StructuredSemanticPlan parsed = parsePlan(raw, catalogue);
        out.put("after_bindColumn_top_level_dimensions", parsed.dimensions());
        out.put("after_bindColumn_top_level_metric", parsed.metric());
        out.put("execution_mode", parsed.executionMode());

        var cqm = canonicalAdapter.adapt(parsed);
        out.put("canonical_partition_column",
                cqm.partition() != null ? cqm.partition().column() : null);

        var validation = SemanticPlanValidationResult.ok();
        var analysisPlan = analysisAdapter.toAnalysisPlan(
                QUESTION, catalogue.tableRef(), parsed, validation, catalogue);
        out.put("analysisPlan.dimension", analysisPlan.dimension());
        out.put("analysisPlan.executable", analysisPlan.executable());
        out.put("analysisPlan.blockers", analysisPlan.blockingReasons());

        InvestigationPlan plan = planMapper.map(new GptPlanningOutcome(
                parsed, null, null, List.of(), null, null, parsed.executionMode()));
        if (plan == null) {
            out.put("investigation_plan", null);
            out.put("drop_point", "InvestigationPlanMapper returned null (no investigation_plan)");
            return out;
        }

        out.put("mapped_phenomenon_measure",
                plan.phenomenon() != null && plan.phenomenon().measure() != null
                        ? plan.phenomenon().measure().column() : null);
        out.put("mapped_evidenceRequest.dimension", plan.evidenceRequests().getFirst().dimension());
        out.put("mapped_evidenceRequest.measure_column",
                plan.evidenceRequests().getFirst().measure() != null
                        ? plan.evidenceRequests().getFirst().measure().column() : null);

        var compiled = compiler.compile(plan, catalogue.tableRef());
        out.put("compile_ok", compiled.requests().getFirst().ok());
        out.put("compile_error", compiled.requests().getFirst().error());

        out.put("drop_point", classifyDrop(
                raw.path("dimensions"),
                raw.path("investigation_plan").path("evidence_requests").path(0).path("dimension"),
                parsed.dimensions(),
                plan.evidenceRequests().getFirst().dimension(),
                analysisPlan));
        return out;
    }

    private static String classifyDrop(
            JsonNode gptDims,
            JsonNode gptInvDim,
            List<String> boundDims,
            String mappedDim,
            com.example.BACKEND.catalogue.decision.planning.AnalysisPlan analysisPlan
    ) {
        boolean gptHadTopLevel = gptDims != null && gptDims.isArray() && gptDims.size() > 0
                && !gptDims.get(0).asText("").isBlank();
        boolean gptHadInvDim = gptInvDim != null && !gptInvDim.isNull() && !gptInvDim.isMissingNode()
                && !gptInvDim.asText("").isBlank();
        boolean boundTopLevel = boundDims != null && !boundDims.isEmpty();
        boolean mappedHasDim = mappedDim != null && !mappedDim.isBlank();

        if (gptHadTopLevel && !boundTopLevel) {
            return "DROPPED_AT_bindColumn (top-level dimensions failed catalogue leaf bind)";
        }
        if (boundTopLevel && gptHadInvDim == false && !mappedHasDim) {
            return "DROPPED_AT_InvestigationPlanMapper "
                    + "(top-level dimensions bound, but investigation_plan.dimension null — "
                    + "no dimension propagation, unlike InvestigationPlanMetricBinder)";
        }
        if (gptHadInvDim && mappedHasDim && "geoNetwork".equalsIgnoreCase(mappedDim)) {
            return "PRESENT_AS_PARENT_RECORD in InvestigationPlan (not a leaf)";
        }
        if (!mappedHasDim && analysisPlan.blockingReasons() != null
                && analysisPlan.blockingReasons().stream()
                .anyMatch(b -> b.contains("grouping dimension"))) {
            return "EMPTY_AT_AnalysisPlan — blocker: " + analysisPlan.blockingReasons();
        }
        if (mappedHasDim) {
            return "NOT_DROPPED — dimension available at compile input: " + mappedDim;
        }
        return "DROPPED_OR_MISSING — unknown stage";
    }

    private StructuredSemanticPlan parsePlan(JsonNode node, ApprovedCatalogueSnapshot catalogue)
            throws Exception {
        Method parsePlan = GptStructuredSemanticPlanner.class.getDeclaredMethod(
                "parsePlan", JsonNode.class, ApprovedCatalogueSnapshot.class);
        parsePlan.setAccessible(true);
        return (StructuredSemanticPlan) parsePlan.invoke(
                new GptStructuredSemanticPlanner(null, json), node, catalogue);
    }

    private ApprovedCatalogueSnapshot gaNestedCatalogue() throws Exception {
        String snapshotJson = """
                {
                  "tables": [{
                    "tableName": "ga_sessions_20160801",
                    "tableSchema": "google_analytics_sample",
                    "columns": [
                      {"columnName":"geoNetwork","role":"container","dataType":"RECORD","container":true},
                      {"columnName":"geoNetwork.country","role":"dimension","dataType":"STRING","parentPath":"geoNetwork"},
                      {"columnName":"geoNetwork.city","role":"dimension","dataType":"STRING","parentPath":"geoNetwork"},
                      {"columnName":"channelGrouping","role":"dimension","dataType":"STRING"},
                      {"columnName":"visitorId","role":"metric","dataType":"INTEGER","aggregationMethod":"COUNT"},
                      {"columnName":"visitId","role":"metric","dataType":"INTEGER","aggregationMethod":"COUNT"}
                    ]
                  }]
                }
                """;
        return SemanticCatalogueFactory.catalogueFrom(
                json.readTree(snapshotJson),
                new RegistryResolutionBundle(
                        List.of(new EntityDescriptor(
                                "ga_sessions_20160801", "ga_sessions_20160801", List.of(), List.of())),
                        List.of(new MetricDescriptor(
                                "visitorId", "ga_sessions_20160801.visitorId", "INTEGER", "COUNT", null)),
                        List.of(new DimensionDescriptor(
                                "geoNetwork.country",
                                "ga_sessions_20160801.geoNetwork.country",
                                "CATEGORICAL")),
                        new ObjectiveDescriptor("RANKING", "RANKING", List.of())));
    }
}
