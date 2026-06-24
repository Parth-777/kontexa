package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Prompt and JSON schema for warehouse-grounded answer synthesis.
 */
public final class AnswerSynthesisPrompt {

    private AnswerSynthesisPrompt() {}

    public static String systemPrompt() {
        return """
                You are an executive analytics interpreter briefing senior business leaders.
                
                SQL planning, warehouse retrieval, and executive presentation are already complete.
                The reader can already see KPIs, tables, charts, insights, and rankings from the presentation layer.
                
                Your role is to deliver an executive-quality narrative — not to construct or recreate the presentation.
                
                Rules:
                - Use ONLY values that appear in the executive presentation statistics, KPIs, table rows,
                  insights, and highlights, or the warehouse rows. Never invent numbers, rankings, percentages,
                  or comparisons.
                - Treat presentation.statistics as the authoritative pre-computed quantitative evidence.
                  Explain those statistics professionally; do not recompute them from raw rows.
                - Lead with the single most important business takeaway in the executive summary.
                - Be quantitative: cite specific values from presentation.statistics and formatted KPIs/table cells.
                - Reference rankings, largest/smallest values, gaps, growth rates, share %, and outliers using
                  the statistics block when present.
                - For GROWTH, PARETO, OUTLIER, or VARIANCE presentations, interpret the specialized table columns shown.
                - Never repeat the entire table row-by-row.
                - Assume the reader can already see the formatted presentation.
                - Mention totals only when they appear in presentation KPIs or warehouse rows.
                - If evidence is insufficient, say what is missing instead of guessing.
                - Suggested follow-up questions must be grounded in what the data could plausibly explore next.
                """;
    }

    public static String userPrompt(AnswerSynthesisInput input, ObjectMapper mapper) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question:\n").append(input.question()).append("\n\n");

        if (input.canonicalQueryModel() != null) {
            try {
                sb.append("Canonical query model:\n")
                        .append(mapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(input.canonicalQueryModel()))
                        .append("\n\n");
            } catch (Exception e) {
                sb.append("Canonical query model: (serialization failed)\n\n");
            }
        }

        if (input.presentation() != null && input.presentation().hasContent()) {
            try {
                sb.append("Executive presentation (PRIMARY evidence — already shown to the reader):\n")
                        .append(mapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(input.presentation().toMap()))
                        .append("\n\n");
            } catch (Exception e) {
                sb.append("Executive presentation: (serialization failed)\n\n");
            }
        }

        try {
            sb.append("Warehouse rows (source of truth for verification only):\n")
                    .append(mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(input.warehouseRows()))
                    .append("\n\n");
        } catch (Exception e) {
            sb.append("Warehouse rows: (serialization failed)\n\n");
        }

        if ((input.presentation() == null || !input.presentation().hasContent())
                && input.executiveTable() != null && input.executiveTable().hasContent()) {
            try {
                sb.append("Formatted executive table (already shown to the reader — interpret, do not recreate):\n")
                        .append(mapper.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(input.executiveTable()))
                        .append("\n\n");
            } catch (Exception e) {
                sb.append("Formatted executive table: (serialization failed)\n\n");
            }
        }

        sb.append("""
                Write an executive summary and up to 5 key findings that interpret the presentation above.
                Prioritize presentation.statistics, then KPIs and table cells, over raw warehouse rows.
                Use specific numbers from the statistics block when available — never estimate or round differently.
                Highlight rankings, top/bottom values, comparisons, growth, concentration, outliers, and notable gaps
                using the pre-computed statistics.
                Do not reproduce the full table in prose.
                """);
        return sb.toString();
    }

    public static JsonNode responseSchema(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);

        ObjectNode props = mapper.createObjectNode();
        props.set("executiveSummary", stringProp(mapper,
                "2-4 sentence executive summary with quantitative highlights from the presentation"));
        props.set("keyFindings", arrayOfStrings(mapper,
                "Up to 5 factual bullets citing specific values from the presentation"));
        props.set("confidenceExplanation", stringProp(mapper, "Brief note on evidence strength"));
        props.set("suggestedVisualization", stringProp(mapper, "BAR, LINE, TABLE, DONUT, or NONE"));
        props.set("answerType", stringProp(mapper,
                "SCALAR, GROUPED, TREND, GROWTH, PARETO, OUTLIER, VARIANCE, CORRELATION, or OTHER"));
        props.set("followUpQuestions", arrayOfStrings(mapper,
                "Up to 3 grounded follow-up questions a leader might ask next"));
        root.set("properties", props);

        ArrayNode required = mapper.createArrayNode();
        required.add("executiveSummary");
        required.add("keyFindings");
        required.add("confidenceExplanation");
        required.add("suggestedVisualization");
        required.add("answerType");
        required.add("followUpQuestions");
        root.set("required", required);
        return root;
    }

    private static ObjectNode stringProp(ObjectMapper mapper, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }

    private static ObjectNode arrayOfStrings(ObjectMapper mapper, String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "array");
        n.put("description", description);
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        n.set("items", items);
        return n;
    }
}
