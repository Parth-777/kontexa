package com.example.BACKEND.catalogue.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses planner-emitted investigation_plan into a typed evidence program.
 * Fail-soft: missing additive reasoning fields default to empty/null.
 */
@Component
public class InvestigationPlanParser {

    public InvestigationPlan parse(JsonNode root, InvestigationObjectiveParser objectiveParser) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return null;
        }
        JsonNode phenomenonNode = root.path("phenomenon");
        String subject = textOrNull(phenomenonNode, "subject");
        InvestigationObjective.Measure phenomenonMeasure =
                parseMeasure(phenomenonNode.path("measure"), objectiveParser);

        List<InvestigationObjective.Filter> scopeFilters = readFilters(root.path("scope").path("filters"));
        List<InvestigationPlan.Hypothesis> hypotheses = readHypotheses(root.path("hypotheses"));
        List<InvestigationPlan.EvidenceRequest> requests =
                readRequests(root.path("evidence_requests"), objectiveParser);
        InvestigationPlan.Insufficiency insufficiency = readInsufficiency(root.path("insufficiency"));
        double confidence = root.path("semantic_confidence").isMissingNode()
                ? root.path("confidence").asDouble(0.5)
                : root.path("semantic_confidence").asDouble(0.5);

        return new InvestigationPlan(
                new InvestigationPlan.Phenomenon(subject, phenomenonMeasure),
                List.copyOf(scopeFilters),
                List.copyOf(hypotheses),
                List.copyOf(requests),
                insufficiency,
                confidence);
    }

    private List<InvestigationPlan.Hypothesis> readHypotheses(JsonNode arr) {
        List<InvestigationPlan.Hypothesis> out = new ArrayList<>();
        if (arr == null || arr.isNull() || arr.isMissingNode() || !arr.isArray()) {
            return out;
        }
        int index = 0;
        for (JsonNode node : arr) {
            if (node == null || node.isNull()) {
                continue;
            }
            String id = textOrNull(node, "id");
            if (id == null || id.isBlank()) {
                id = "H" + (index + 1);
            }
            String claim = textOrNull(node, "claim");
            if (claim == null || claim.isBlank()) {
                claim = textOrNull(node, "statement");
            }
            if (claim == null || claim.isBlank()) {
                index++;
                continue;
            }
            String status = normalizeHypothesisStatus(textOrNull(node, "status"));
            out.add(new InvestigationPlan.Hypothesis(id, claim, status));
            index++;
        }
        return out;
    }

    private InvestigationPlan.Insufficiency readInsufficiency(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        boolean answerable = !node.has("answerable") || node.path("answerable").asBoolean(true);
        List<String> unknowns = readStringList(node.path("unknowns"));
        List<String> blocked = readStringList(node.path("blocked_reasons"));
        if (blocked.isEmpty()) {
            blocked = readStringList(node.path("blockedReasons"));
        }
        return new InvestigationPlan.Insufficiency(answerable, unknowns, blocked);
    }

    private List<InvestigationPlan.EvidenceRequest> readRequests(
            JsonNode arr,
            InvestigationObjectiveParser objectiveParser
    ) {
        List<InvestigationPlan.EvidenceRequest> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        int index = 0;
        for (JsonNode node : arr) {
            String id = textOrNull(node, "id");
            if (id == null || id.isBlank()) {
                id = textOrNull(node, "request_id");
            }
            if (id == null || id.isBlank()) {
                id = "ev_" + index;
            }
            String kind = textOrNull(node, "kind");
            if (kind == null || kind.isBlank()) {
                kind = textOrNull(node, "type");
            }
            String role = textOrNull(node, "role");
            InvestigationObjective.Measure measure = parseMeasure(node.path("measure"), objectiveParser);
            String dimension = textOrNull(node, "dimension");
            if (dimension == null || dimension.isBlank()) {
                dimension = textOrNull(node, "column");
            }

            InvestigationObjective.Slice slice = objectiveParser.parseSliceNode(node.path("slice"));
            InvestigationObjective.Slice focal = objectiveParser.parseSliceNode(node.path("focal_slice"));
            InvestigationObjective.Slice baseline = objectiveParser.parseSliceNode(node.path("baseline_slice"));
            if ((focal == null || focal.constraints().isEmpty())
                    && node.has("contrast") && !node.path("contrast").isNull()) {
                JsonNode contrast = node.path("contrast");
                focal = objectiveParser.parseSliceNode(contrast.path("focal"));
                baseline = objectiveParser.parseSliceNode(contrast.path("baseline"));
            }

            String hypothesisId = textOrNull(node, "hypothesis_id");
            if (hypothesisId == null || hypothesisId.isBlank()) {
                hypothesisId = textOrNull(node, "hypothesisId");
            }
            String evidencePurpose = normalizeEvidencePurpose(
                    textOrNull(node, "evidence_purpose") != null
                            ? textOrNull(node, "evidence_purpose")
                            : textOrNull(node, "evidencePurpose"));

            out.add(new InvestigationPlan.EvidenceRequest(
                    id,
                    kind != null ? kind : "slice_metric",
                    role,
                    measure,
                    dimension,
                    slice,
                    focal,
                    baseline,
                    node,
                    hypothesisId,
                    evidencePurpose));
            index++;
        }
        return out;
    }

    private InvestigationObjective.Measure parseMeasure(JsonNode node, InvestigationObjectiveParser objectiveParser) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return objectiveParser.parseMeasureNode(node);
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

    private static List<String> readStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || arr.isNull() || arr.isMissingNode() || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n != null && n.isTextual()) {
                String text = n.asText();
                if (text != null && !text.isBlank()) {
                    out.add(text.trim());
                }
            }
        }
        return out;
    }

    private static String normalizeHypothesisStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return InvestigationPlan.Hypothesis.ALTERNATIVE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case InvestigationPlan.Hypothesis.PRIMARY,
                 InvestigationPlan.Hypothesis.ALTERNATIVE,
                 InvestigationPlan.Hypothesis.REJECTED_CANDIDATE -> normalized;
            default -> InvestigationPlan.Hypothesis.ALTERNATIVE;
        };
    }

    private static String normalizeEvidencePurpose(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case InvestigationPlan.EvidenceRequest.PURPOSE_PRIMARY,
                 InvestigationPlan.EvidenceRequest.PURPOSE_VALIDATION,
                 InvestigationPlan.EvidenceRequest.PURPOSE_COMPARISON,
                 InvestigationPlan.EvidenceRequest.PURPOSE_CONTRADICTION,
                 InvestigationPlan.EvidenceRequest.PURPOSE_CONTEXT -> normalized;
            default -> null;
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
