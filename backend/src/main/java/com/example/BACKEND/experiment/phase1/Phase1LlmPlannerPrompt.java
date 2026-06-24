package com.example.BACKEND.experiment.phase1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds system/user prompts and JSON schema for Phase-1 structured planning.
 */
public final class Phase1LlmPlannerPrompt {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Phase1LlmPlannerPrompt() {}

    public static String systemPrompt(Phase1CataloguePayloadMode mode) {
        String catalogueHint = mode == Phase1CataloguePayloadMode.WITH_DESCRIPTIONS
                ? "Use the approved catalogue descriptions to select metrics and dimensions."
                : "Use only column names from the catalogue; descriptions are not provided.";
        return """
                You are a factual analytics query planner.
                Given a business question, a catalogue, and a table schema,
                produce a structured query plan.

                %s

                Rules:
                - Only use column names that appear in the catalogue.
                - Do not invent aliases or columns.
                - Answer factual aggregation questions only (top, total, count, average, breakdown, trend, filter).
                - Reject causal/explanatory questions (why, what caused, what drives, explain relationship).
                  For those, set confidence below 0.4 and metric null.
                - dimensions: grouping columns (0 or 1 primary; empty for scalar filtered totals).
                - filters: equality filters when the question narrows rows.
                - ordering: metric or dimension column with ASC/DESC for ranking questions.
                - limit: use for top/bottom N questions.
                - If ambiguous, set confidence < 0.7 and populate alternatives.
                """.formatted(catalogueHint);
    }

    public static String systemPrompt() {
        return systemPrompt(Phase1CataloguePayloadMode.WITH_DESCRIPTIONS);
    }

    public static String userPrompt(Phase1PlannerInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION:\n").append(input.question()).append("\n\n");
        sb.append("CATALOGUE (table=").append(input.catalogue().tableRef());
        sb.append(", mode=").append(input.catalogue().payloadMode()).append("):\n");
        for (Phase1CatalogueEntry e : input.catalogue().entries()) {
            if (input.catalogue().payloadMode() == Phase1CataloguePayloadMode.WITH_DESCRIPTIONS) {
                sb.append("- {\"column\":\"").append(e.columnName())
                        .append("\",\"description\":\"").append(escape(e.description()))
                        .append("\",\"type\":\"").append(e.role()).append("\"");
                if (e.dataType() != null) {
                    sb.append(",\"data_type\":\"").append(e.dataType()).append("\"");
                }
                if (e.defaultAggregation() != null) {
                    sb.append(",\"default_aggregation\":\"").append(e.defaultAggregation()).append("\"");
                }
                sb.append("}\n");
            } else {
                sb.append("- {\"column\":\"").append(e.columnName())
                        .append("\",\"type\":\"").append(e.role()).append("\"");
                if (e.dataType() != null) {
                    sb.append(",\"data_type\":\"").append(e.dataType()).append("\"");
                }
                sb.append("}\n");
            }
        }
        sb.append("\nSCHEMA:\n");
        for (Phase1SchemaSnapshot.Phase1SchemaColumn c : input.schema().columns()) {
            sb.append("- ").append(c.name()).append(" : ").append(c.type()).append('\n');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static JsonNode responseSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "intent": {
                          "type": "string",
                          "enum": ["RANKING","CONTRIBUTION","TREND","COMPARISON","DISTRIBUTION","SCALAR"]
                        },
                        "metric": { "type": ["string","null"] },
                        "aggregation": {
                          "type": "string",
                          "enum": ["SUM","AVG","COUNT","MIN","MAX"]
                        },
                        "dimensions": {
                          "type": "array",
                          "items": { "type": "string" }
                        },
                        "filters": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "properties": {
                              "column": { "type": "string" },
                              "operator": { "type": "string" },
                              "value": { "type": "string" }
                            },
                            "required": ["column","operator","value"]
                          }
                        },
                        "ordering": {
                          "type": ["object","null"],
                          "additionalProperties": false,
                          "properties": {
                            "column": { "type": "string" },
                            "direction": { "type": "string", "enum": ["ASC","DESC"] }
                          },
                          "required": ["column","direction"]
                        },
                        "limit": { "type": ["integer","null"] },
                        "confidence": { "type": "number" },
                        "reasoning": { "type": "string" },
                        "alternatives": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "properties": {
                              "intent": { "type": "string" },
                              "metric": { "type": ["string","null"] },
                              "aggregation": { "type": "string" },
                              "dimensions": { "type": "array", "items": { "type": "string" } },
                              "filters": {
                                "type": "array",
                                "items": {
                                  "type": "object",
                                  "additionalProperties": false,
                                  "properties": {
                                    "column": { "type": "string" },
                                    "operator": { "type": "string" },
                                    "value": { "type": "string" }
                                  },
                                  "required": ["column","operator","value"]
                                }
                              },
                              "ordering": {
                                "type": ["object","null"],
                                "additionalProperties": false,
                                "properties": {
                                  "column": { "type": "string" },
                                  "direction": { "type": "string" }
                                },
                                "required": ["column","direction"]
                              },
                              "limit": { "type": ["integer","null"] },
                              "confidence": { "type": "number" },
                              "reasoning": { "type": "string" }
                            },
                            "required": ["intent","metric","aggregation","dimensions","filters","ordering","limit","confidence","reasoning"]
                          }
                        }
                      },
                      "required": ["intent","metric","aggregation","dimensions","filters","ordering","limit","confidence","reasoning","alternatives"]
                    }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid embedded JSON schema", e);
        }
    }
}