package com.example.BACKEND.catalogue.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses GPT {@code investigation_objective} JSON into the runtime contract.
 */
@Component
public class InvestigationObjectiveParser {

    public InvestigationObjective parse(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return null;
        }
        InvestigationObjective.Measure measure = parseMeasureNode(root.path("measure"));

        List<InvestigationObjective.Filter> scopeFilters =
                readFilters(root.path("scope").path("filters"));

        JsonNode comparison = root.path("comparison");
        InvestigationObjective.Slice focal = parseSliceNode(comparison.path("focal"));
        InvestigationObjective.Slice baseline = parseSliceNode(comparison.path("baseline"));

        JsonNode decomp = root.path("decomposition");
        List<String> dimensions = readStringList(decomp.path("dimensions"));
        List<String> exclude = readStringList(decomp.path("exclude"));

        String salience = textOrNull(root, "salience");
        if (salience == null || salience.isBlank()) {
            salience = InvestigationObjective.SALIENCE_DIFFERENCE;
        }
        double confidence = root.path("semantic_confidence").isMissingNode()
                ? root.path("confidence").asDouble(0.5)
                : root.path("semantic_confidence").asDouble(0.5);

        return new InvestigationObjective(
                measure,
                new InvestigationObjective.Scope(List.copyOf(scopeFilters)),
                new InvestigationObjective.Comparison(focal, baseline),
                new InvestigationObjective.Decomposition(List.copyOf(dimensions), List.copyOf(exclude)),
                salience.trim().toUpperCase(),
                confidence);
    }

    public InvestigationObjective.Measure parseMeasureNode(JsonNode measureNode) {
        if (measureNode == null || measureNode.isNull() || measureNode.isMissingNode()) {
            return null;
        }
        String column = textOrNull(measureNode, "column");
        String aggregation = normalizeAgg(textOrNull(measureNode, "aggregation"));
        List<String> grain = readStringList(measureNode.path("grain"));
        return new InvestigationObjective.Measure(column, aggregation, List.copyOf(grain));
    }

    public InvestigationObjective.Slice parseSliceNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new InvestigationObjective.Slice(List.of());
        }
        List<InvestigationObjective.Constraint> constraints = new ArrayList<>();
        JsonNode arr = node.path("constraints");
        if (arr.isArray()) {
            for (JsonNode c : arr) {
                InvestigationObjective.Constraint parsed = readConstraint(c);
                if (parsed != null) {
                    constraints.add(parsed);
                }
            }
        }
        return new InvestigationObjective.Slice(List.copyOf(constraints));
    }

    private InvestigationObjective.Constraint readConstraint(JsonNode node) {
        if (node.has("filter") && !node.path("filter").isNull()) {
            JsonNode f = node.path("filter");
            return new InvestigationObjective.Constraint(
                    new InvestigationObjective.Filter(
                            f.path("column").asText(),
                            f.path("operator").asText("="),
                            f.path("value").asText("")),
                    null, null, null);
        }
        if (node.has("time_range") && !node.path("time_range").isNull()) {
            JsonNode t = node.path("time_range");
            return new InvestigationObjective.Constraint(
                    null,
                    new InvestigationObjective.TimeRange(
                            t.path("column").asText(),
                            t.path("start").asText(),
                            t.path("end").asText()),
                    null, null);
        }
        if (node.has("rank") && !node.path("rank").isNull()) {
            JsonNode r = node.path("rank");
            return new InvestigationObjective.Constraint(
                    null, null,
                    new InvestigationObjective.Rank(
                            r.path("column").asText(),
                            r.path("side").asText("LOWER"),
                            r.path("fraction").asDouble(0.1),
                            readStringList(r.path("grain"))),
                    null);
        }
        if (node.has("population") && !node.path("population").isNull()
                && node.path("population").asBoolean(false)) {
            return new InvestigationObjective.Constraint(null, null, null, true);
        }
        return null;
    }

    private List<InvestigationObjective.Filter> readFilters(JsonNode arr) {
        List<InvestigationObjective.Filter> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            out.add(new InvestigationObjective.Filter(
                    n.path("column").asText(),
                    n.path("operator").asText("="),
                    n.path("value").asText("")));
        }
        return out;
    }

    private List<String> readStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (!n.isNull()) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static String normalizeAgg(String agg) {
        if (agg == null || agg.isBlank()) {
            return InvestigationObjective.AGG_SUM;
        }
        return agg.trim().toUpperCase();
    }
}
