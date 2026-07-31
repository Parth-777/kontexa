package com.example.BACKEND.catalogue.semantic.phase2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls GPT and parses a {@link StructuredSemanticPlan}.
 */
@Component
public class GptStructuredSemanticPlanner {

    private static final Logger log = LoggerFactory.getLogger(GptStructuredSemanticPlanner.class);

    private final GptStructuredCompletionClient client;
    private final ObjectMapper mapper;

    public GptStructuredSemanticPlanner(
            GptStructuredCompletionClient client,
            ObjectMapper mapper
    ) {
        this.client = client;
        this.mapper = mapper;
    }

    public StructuredSemanticPlan plan(
            String question,
            ApprovedCatalogueSnapshot catalogue,
            SchemaSnapshot schema
    ) {
        String raw = client.completeStructured(
                GptStructuredPlannerPrompt.systemPrompt(),
                GptStructuredPlannerPrompt.userPrompt(question, catalogue, schema),
                GptStructuredPlannerPrompt.responseSchema());
        log.info("[planner-pipeline-trace] stage=raw_gpt_json question={} payload={}",
                question, raw);
        try {
            JsonNode root = mapper.readTree(raw);
            String rawMetric = textOrNull(root, "metric");
            log.info("[planner-pipeline-trace] stage=metric_before_bind question={} metric={}",
                    question, rawMetric);
            StructuredSemanticPlan plan = parsePlan(root, catalogue);
            log.info("[planner-pipeline-trace] stage=metric_after_bind question={} metric={}",
                    question, plan.metric());
            log.info("[planner-pipeline-trace] stage=structured_semantic_plan question={} payload={}",
                    question, mapper.writeValueAsString(plan));
            return plan;
        } catch (Exception e) {
            throw new RuntimeException("GPT returned non-JSON plan: " + raw, e);
        }
    }

    private StructuredSemanticPlan parsePlan(JsonNode node, ApprovedCatalogueSnapshot catalogue) {
        List<String> dims = bindColumns(catalogue, readStringList(node.path("dimensions")));
        String metric = bindColumn(catalogue, textOrNull(node, "metric"), true);
        String secondary = bindColumn(catalogue, textOrNull(node, "secondary_metric"), true);
        String relationship = bindColumn(catalogue, textOrNull(node, "relationship_variable"), true);

        StructuredSemanticPlan.SemanticOrdering ordering = null;
        JsonNode ord = node.path("ordering");
        if (!ord.isMissingNode() && !ord.isNull()) {
            String col = bindColumn(catalogue, ord.path("column").asText(null), false);
            if (col != null) {
                ordering = new StructuredSemanticPlan.SemanticOrdering(
                        col, ord.path("direction").asText("DESC"));
            }
        }

        Integer limit = node.path("limit").isNull() || node.path("limit").isMissingNode()
                ? null : node.path("limit").asInt();
        String timeGrain = textOrNull(node, "time_grain");

        JsonNode aggs = node.path("aggregations");
        StructuredSemanticPlan.SemanticAggregations aggregations = new StructuredSemanticPlan.SemanticAggregations(
                aggs.path("primary").isNull() ? null : aggs.path("primary").asText(null),
                aggs.path("secondary").isNull() ? null : aggs.path("secondary").asText(null));

        List<StructuredSemanticPlan> alternatives = new ArrayList<>();
        JsonNode alt = node.path("alternatives");
        if (alt.isArray()) {
            for (JsonNode a : alt) {
                alternatives.add(parsePlan(a, catalogue));
            }
        }

        String executionMode = normalizeMode(textOrNull(node, "execution_mode"));
        String investigationDirection = StructuredSemanticPlan.MODE_INVESTIGATION.equals(executionMode)
                ? textOrNull(node, "investigation_direction")
                : null;

        return new StructuredSemanticPlan(
                node.path("intent").asText("DISTRIBUTION"),
                metric,
                secondary,
                dims,
                readFilters(node.path("filters"), catalogue),
                aggregations,
                ordering,
                limit,
                relationship,
                timeGrain,
                node.path("confidence").asDouble(0.5),
                node.path("reasoning").asText(""),
                List.copyOf(alternatives),
                executionMode,
                investigationDirection,
                node.path("investigation_objective").isMissingNode()
                        ? null : node.path("investigation_objective"),
                node.path("investigation_plan").isMissingNode()
                        ? null : node.path("investigation_plan"));
    }

    private static List<StructuredSemanticPlan.SemanticFilter> readFilters(
            JsonNode arr, ApprovedCatalogueSnapshot catalogue
    ) {
        List<StructuredSemanticPlan.SemanticFilter> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String col = bindColumn(catalogue, n.path("column").asText(null), false);
            if (col == null) continue;
            out.add(new StructuredSemanticPlan.SemanticFilter(
                    col,
                    n.path("operator").asText("="),
                    n.path("value").asText("")));
        }
        return List.copyOf(out);
    }

    private static List<String> readStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) out.add(n.asText());
        return out;
    }

    private static List<String> bindColumns(ApprovedCatalogueSnapshot catalogue, List<String> cols) {
        List<String> out = new ArrayList<>();
        for (String c : cols) {
            String bound = bindColumn(catalogue, c, false);
            if (bound != null) out.add(bound);
        }
        return out;
    }

    private static String bindColumn(ApprovedCatalogueSnapshot catalogue, String col, boolean metricOk) {
        if (col == null || col.isBlank()) {
            logBindFailure(col, "blank_or_null");
            return null;
        }
        if (!catalogue.hasColumn(col)) {
            String reason = diagnoseMissingColumn(catalogue, col);
            logBindFailure(col, reason);
            return null;
        }
        if (!catalogue.isBindableLeaf(col)) {
            logBindFailure(col, "not_bindable_leaf (container/parent path)");
            return null;
        }
        if (metricOk && catalogue.metricColumns().stream().noneMatch(m -> m.equalsIgnoreCase(col))
                && catalogue.dimensionColumns().stream().noneMatch(d -> d.equalsIgnoreCase(col))) {
            String bound = catalogue.isBindableLeaf(col) ? catalogue.canonicalColumnName(col) : null;
            if (bound == null) {
                logBindFailure(col, "leaf_not_in_metric_or_dimension_role_lists");
            }
            return bound;
        }
        return catalogue.canonicalColumnName(col);
    }

    private static void logBindFailure(String col, String reason) {
        log.info("[planner-pipeline-trace] stage=bindColumn_failed raw={} reason={}", col, reason);
    }

    private static String diagnoseMissingColumn(ApprovedCatalogueSnapshot catalogue, String col) {
        String trimmed = col == null ? "" : col.trim();
        if (trimmed.matches("(?i)^[A-Z_]+\\(.+\\)$")) {
            return "wrapper_expression_not_catalogue_leaf (e.g. SUM(...)/COUNT(...)); exact path required";
        }
        int dot = trimmed.indexOf('.');
        if (dot > 0) {
            String maybeTableQualified = trimmed.substring(dot + 1);
            if (catalogue.hasColumn(maybeTableQualified)) {
                return "table_qualified_path_mismatch; catalogue leaf is '" + maybeTableQualified
                        + "' but GPT returned '" + trimmed + "'";
            }
        }
        // Strip SUM(table.col) / SUM(col) shapes for clearer diagnostics
        Matcher m = Pattern.compile("(?i)^([A-Z_]+)\\((.+)\\)$").matcher(trimmed);
        if (m.matches()) {
            String inner = m.group(2).trim();
            int innerDot = inner.indexOf('.');
            String innerLeaf = innerDot > 0 ? inner.substring(innerDot + 1) : inner;
            if (catalogue.hasColumn(inner) || catalogue.hasColumn(innerLeaf)) {
                return "wrapper_expression_not_catalogue_leaf; inner path exists as '"
                        + (catalogue.hasColumn(inner) ? inner : innerLeaf)
                        + "' but GPT returned '" + trimmed + "'";
            }
            return "wrapper_expression_not_catalogue_leaf; GPT returned '" + trimmed + "'";
        }
        return "exact_string_mismatch; no catalogue column named '" + trimmed + "'";
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static String normalizeMode(String raw) {
        if (raw != null && StructuredSemanticPlan.MODE_INVESTIGATION.equalsIgnoreCase(raw.trim())) {
            return StructuredSemanticPlan.MODE_INVESTIGATION;
        }
        return StructuredSemanticPlan.MODE_CANONICAL;
    }
}
