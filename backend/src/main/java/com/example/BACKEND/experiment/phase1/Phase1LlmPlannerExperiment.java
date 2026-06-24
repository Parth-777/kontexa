package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Phase-1 isolated prototype: question + catalogue + schema → GPT → QuerySpec → SQL.
 *
 * No aliases, regex intent detection, or production semantic extractors.
 */
public final class Phase1LlmPlannerExperiment {

    public static final double LOW_CONFIDENCE_THRESHOLD = 0.7;

    private final Phase1LlmClient llm;
    private final DeterministicAnalyticalQueryPlanner sqlPlanner;
    private final ObjectMapper mapper;

    public Phase1LlmPlannerExperiment(
            Phase1LlmClient llm,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            ObjectMapper mapper
    ) {
        this.llm = llm;
        this.sqlPlanner = sqlPlanner;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public Phase1PlannerOutput plan(Phase1PlannerInput input, RegistryResolutionBundle bundle) {
        String raw = llm.completeStructured(
                Phase1LlmPlannerPrompt.systemPrompt(input.catalogue().payloadMode()),
                Phase1LlmPlannerPrompt.userPrompt(input),
                Phase1LlmPlannerPrompt.responseSchema());

        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException("LLM returned non-JSON: " + raw, e);
        }

        Phase1PlannerCandidate primary = parseCandidate(root, input.catalogue());
        List<Phase1PlannerCandidate> alternates = new ArrayList<>();
        if (primary.confidence() < LOW_CONFIDENCE_THRESHOLD) {
            JsonNode alt = root.path("alternatives");
            if (alt.isArray()) {
                for (JsonNode node : alt) {
                    alternates.add(parseCandidate(node, input.catalogue()));
                }
            }
        }

        List<QuerySpec> specs = toQuerySpecs(input, bundle, primary);
        return new Phase1PlannerOutput(primary, List.copyOf(alternates), specs);
    }

    private List<QuerySpec> toQuerySpecs(
            Phase1PlannerInput input,
            RegistryResolutionBundle bundle,
            Phase1PlannerCandidate candidate
    ) {
        if (Phase1ScalarSqlBuilder.isScalarFiltered(candidate)) {
            return List.of(Phase1ScalarSqlBuilder.build(input.catalogue().tableRef(), candidate));
        }
        AnalysisPlan plan = Phase1AnalysisPlanAdapter.toAnalysisPlan(
                input.question(),
                input.catalogue().tableRef(),
                candidate);
        List<QuerySpec> base = sqlPlanner.plan(plan, bundle);
        return Phase1FilterSqlAugmenter.applyFilters(base, candidate.filters());
    }

    private Phase1PlannerCandidate parseCandidate(JsonNode node, Phase1CatalogueSnapshot catalogue) {
        String metric = Phase1ColumnValidator.validateMetric(catalogue, textOrNull(node, "metric"));
        List<String> dims = Phase1ColumnValidator.validateColumns(
                catalogue, readStringList(node.path("dimensions")), Set.of("dimension"));
        List<Phase1FilterSpec> filters = Phase1ColumnValidator.validateFilters(
                catalogue, readFilters(node.path("filters")));

        Phase1OrderingSpec ordering = null;
        JsonNode ord = node.path("ordering");
        if (!ord.isMissingNode() && !ord.isNull()) {
            String col = ord.path("column").asText(null);
            if (col != null && catalogue.allColumns().stream().anyMatch(c -> c.equalsIgnoreCase(col))) {
                ordering = new Phase1OrderingSpec(col, ord.path("direction").asText("DESC"));
            }
        }

        Integer limit = node.path("limit").isNull() || node.path("limit").isMissingNode()
                ? null : node.path("limit").asInt();

        return new Phase1PlannerCandidate(
                Phase1ColumnValidator.parseIntent(node.path("intent").asText()),
                metric,
                node.path("aggregation").asText("SUM"),
                dims,
                filters,
                ordering,
                limit,
                node.path("confidence").asDouble(0.5),
                node.path("reasoning").asText(""));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static List<String> readStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) out.add(n.asText());
        return out;
    }

    private static List<Phase1FilterSpec> readFilters(JsonNode arr) {
        List<Phase1FilterSpec> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            out.add(new Phase1FilterSpec(
                    n.path("column").asText(),
                    n.path("operator").asText("="),
                    n.path("value").asText("")));
        }
        return out;
    }
}
