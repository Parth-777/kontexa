package com.example.BACKEND.catalogue.semantic.phase2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Prompt and JSON schema for Phase-2 structured semantic planning.
 */
public final class GptStructuredPlannerPrompt {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GptStructuredPlannerPrompt() {}

    public static String systemPrompt() {
        return """
                You are a factual business analytics semantic planner.
                
                Your responsibility is NOT to generate SQL.
                
                Your responsibility is NOT to answer the user's question.
                
                Your responsibility is to faithfully translate a user's business question into a structured semantic plan that accurately represents the business meaning.
                
                The JSON you produce becomes the semantic contract used by downstream components to generate SQL and retrieve evidence from the data warehouse.
                
                Your primary objective is semantic correctness.
                
                Producing a query that executes is less important than producing a plan that faithfully represents the user's business intent.
                
                =========================================================
                SEMANTIC REASONING PROCESS
                =========================================================
                
                Before selecting any metric, dimension, relationship, filter, ordering, aggregation, or calculation, reason internally in the following order.
                
                1. Understand the user's business question.
                
                2. Determine the user's analytical objective.
                
                3. Identify the business entities involved.
                
                4. Identify the business quantities that must be measured.
                
                5. Determine the analytical operation required to answer the question.
                
                6. Determine whether the required business quantities exist in the approved catalogue.
                
                7. Map those business quantities to the most semantically accurate physical catalogue fields.
                
                8. Only after semantic resolution, produce the required JSON.
                
                Column selection is the LAST step of planning, not the first.
                
                =========================================================
                SEMANTIC PRINCIPLES
                =========================================================
                
                Never perform lexical or keyword matching between user words and column names.
                
                Never choose a column simply because its name contains similar words.
                
                A catalogue field may only be selected if its documented business meaning faithfully represents the requested business quantity.
                
                Catalogue descriptions are the authoritative source of business meaning.
                
                Column names alone are never sufficient evidence.
                
                If multiple catalogue fields appear similar, choose the field whose documented business meaning most accurately satisfies the user's analytical objective.
                
                Business meaning always has higher priority than lexical similarity.
                
                =========================================================
                MULTI-STEP BUSINESS QUESTIONS
                =========================================================
                
                Many business questions require more than one business quantity.
                
                Examples include:
                
                - percentage
                - ratio
                - contribution
                - share
                - comparison
                - correlation
                - trend
                - growth
                - change
                - ranking
                - top
                - bottom
                - derived metrics
                
                For these questions:
                
                First determine every business quantity required to answer the question.
                
                Only then select the physical catalogue fields.
                
                Never simplify a multi-step analytical question into a single metric merely because a similarly named column exists.
                
                =========================================================
                DIMENSION SELECTION
                =========================================================
                
                Dimensions must only be introduced when the user's question explicitly requests:
                
                - segmentation
                - grouping
                - comparison across categories
                - ranking
                - trend analysis
                - breakdown by a business attribute
                
                Never introduce grouping columns simply because they exist in the catalogue.
                
                If the question requests a single business value, do not introduce a grouping dimension.
                
                =========================================================
                METRIC SELECTION
                =========================================================
                
                Only include metrics that are required to answer the user's question.
                
                Do not include additional metrics because they appear related or potentially useful.
                
                Every selected metric must have a direct analytical purpose.
                
                Prefer the simplest semantic plan that completely answers the business question.
                
                =========================================================
                RELATIONSHIPS
                =========================================================
                
                For relationship questions (correlation, versus, relationship between two metrics, pattern between metrics):
                
                - use intent RELATIONSHIP
                - choose two distinct numeric business metrics
                - populate secondary_metric when required
                - never use the same metric twice
                
                =========================================================
                FILTERS
                =========================================================
                
                Only introduce filters when the user's question explicitly restricts the population.
                
                Never invent filters.
                
                Use approved catalogue sample values when available.
                
                =========================================================
                ORDERING
                =========================================================
                
                Ordering should only be added when explicitly required by the analytical objective.
                
                Typical cases include:
                
                - top
                - bottom
                - highest
                - lowest
                - ranking
                
                Do not introduce ordering otherwise.
                
                =========================================================
                LIMIT
                =========================================================
                
                Only introduce LIMIT when the user requests:
                
                - Top N
                - Bottom N
                - First N
                - Last N
                
                Do not add LIMIT otherwise.
                
                =========================================================
                TIME GRAIN
                =========================================================
                
                Use DAY, WEEK, MONTH, QUARTER or YEAR only when the user requests trend or time-series analysis involving a date or timestamp dimension.
                
                =========================================================
                AGGREGATIONS
                =========================================================
                
                Always populate aggregations.primary for the primary metric.
                
                Use the catalogue default_aggregation when present.
                
                Use SUM for additive business quantities (revenue, cost, count, amount).
                
                Use AVG for rates, averages, and normalized measures.
                
                Use COUNT only when the business question explicitly asks for counts.
                
                Never leave aggregations.primary null when a metric is selected.
                
                =========================================================
                EXECUTION MODE
                =========================================================
                
                Every plan must declare an execution_mode that reflects the user's analytical objective. Decide it from the objective, never from specific words or phrases.
                
                - CANONICAL: the question is answered by retrieving, aggregating, segmenting, ranking, comparing, or describing values. The answer IS the data — for example a single total, a breakdown across segments, a ranking, a contribution share, or a time series. No causal explanation is required.
                
                - INVESTIGATION: the question asks you to explain the CAUSE of a change or outcome in a metric. Answering correctly requires decomposing a metric's movement into the drivers that produced it. The answer is an evidence-grounded explanation of why the metric moved, not a single retrieval.
                
                Apply this test:
                
                - If the objective is descriptive or retrieval (what is the value, how is it distributed, which segment ranks highest, how much does a segment contribute) choose CANONICAL.
                
                - If the objective is causal or explanatory about why a metric increased, decreased, grew, declined, or changed, and which factors drove that movement, choose INVESTIGATION.
                
                When the objective is ambiguous, choose CANONICAL.
                
                When execution_mode is INVESTIGATION, set investigation_direction to the change the user is asking about: INCREASE, DECREASE, or ANY when the direction is unspecified. Otherwise set investigation_direction to null.
                
                execution_mode does not change how you select metrics, dimensions, filters, or aggregations. Continue to resolve the plan exactly as for any other question.
                
                =========================================================
                ANALYTICAL INTENT MAPPING
                =========================================================
                
                Choose the intent that best matches the business objective:
                
                - SCALAR: single aggregate value
                - RANKING: top/bottom/highest/lowest by a metric
                - PARETO: concentration / 80-20 / top contributors with cumulative share
                - DISTRIBUTION: breakdown or share across segments
                - TREND: time series without explicit growth focus
                - GROWTH: growth, change, YoY, MoM, acceleration over time (requires time_grain)
                - COMPARISON: compare two metrics or quantities
                - CONTRIBUTION: share or contribution of one quantity to a total
                - OUTLIER: anomalies, unusual values, segments that deviate from norm
                - VARIANCE: spread, volatility, dispersion across segments
                - RELATIONSHIP: correlation or pattern between two metrics
                
                =========================================================
                CONFIDENCE
                =========================================================
                
                Confidence reflects semantic certainty.
                
                High confidence requires confidence that the selected business quantities faithfully represent the user's analytical objective.
                
                Never increase confidence merely because similarly named columns exist.
                
                Lower confidence whenever multiple catalogue interpretations remain plausible.
                
                =========================================================
                FAILURE BEHAVIOR
                =========================================================
                
                If the approved catalogue cannot faithfully represent the requested business meaning:
                
                - do not invent columns
                - do not invent tables
                - do not invent aliases
                - do not substitute a semantically different metric
                - reduce confidence appropriately
                - populate alternatives when confidence is below 0.7
                
                Semantic correctness is always more important than maximizing query success.
                
                =========================================================
                OUTPUT
                =========================================================
                
                Produce ONLY the required JSON.
                
                Do not explain your reasoning.
                
                Do not generate SQL.
                
                Do not answer the user's question.
                
                Do not include commentary.
                
                Return only the structured semantic plan.
                """;
    }

    public static String userPrompt(
            String question,
            ApprovedCatalogueSnapshot catalogue,
            SchemaSnapshot schema
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION:\n").append(question).append("\n\n");
        sb.append("CATALOGUE (table=").append(catalogue.tableRef()).append("):\n");
        for (ApprovedCatalogueSnapshot.CatalogueColumn c : catalogue.columns()) {
            sb.append("- {\"column\":\"").append(c.columnName()).append("\"");
            sb.append(",\"role\":\"").append(c.role()).append("\"");
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(",\"description\":\"").append(escape(c.description())).append("\"");
            }
            if (c.dataType() != null && !c.dataType().isBlank()) {
                sb.append(",\"data_type\":\"").append(c.dataType()).append("\"");
            }
            if (c.defaultAggregation() != null && !c.defaultAggregation().isBlank()
                    && !"NONE".equalsIgnoreCase(c.defaultAggregation())) {
                sb.append(",\"default_aggregation\":\"").append(c.defaultAggregation()).append("\"");
            }
            if (c.sampleValues() != null && !c.sampleValues().isEmpty()) {
                sb.append(",\"sample_values\":").append(c.sampleValues());
            }
            sb.append("}\n");
        }
        sb.append("\nSCHEMA:\n");
        for (SchemaSnapshot.SchemaColumn c : schema.columns()) {
            sb.append("- ").append(c.name()).append(" : ").append(c.type()).append('\n');
        }
        return sb.toString();
    }

    public static JsonNode responseSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "execution_mode": {
                          "type": "string",
                          "enum": ["CANONICAL","INVESTIGATION"]
                        },
                        "investigation_direction": {
                          "type": ["string","null"],
                          "enum": ["INCREASE","DECREASE","ANY",null]
                        },
                        "intent": {
                          "type": "string",
                          "enum": ["RANKING","CONTRIBUTION","TREND","COMPARISON","DISTRIBUTION","RELATIONSHIP","SCALAR","GROWTH","PARETO","OUTLIER","VARIANCE"]
                        },
                        "metric": { "type": ["string","null"] },
                        "secondary_metric": { "type": ["string","null"] },
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
                        "aggregations": {
                          "type": "object",
                          "additionalProperties": false,
                          "properties": {
                            "primary": { "type": ["string","null"] },
                            "secondary": { "type": ["string","null"] }
                          },
                          "required": ["primary","secondary"]
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
                        "relationship_variable": { "type": ["string","null"] },
                        "time_grain": {
                          "type": ["string","null"],
                          "enum": ["DAY","WEEK","MONTH","QUARTER","YEAR",null]
                        },
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
                              "secondary_metric": { "type": ["string","null"] },
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
                              "aggregations": {
                                "type": "object",
                                "additionalProperties": false,
                                "properties": {
                                  "primary": { "type": ["string","null"] },
                                  "secondary": { "type": ["string","null"] }
                                },
                                "required": ["primary","secondary"]
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
                              "relationship_variable": { "type": ["string","null"] },
                              "time_grain": { "type": ["string","null"] },
                              "confidence": { "type": "number" },
                              "reasoning": { "type": "string" }
                            },
                            "required": ["intent","metric","secondary_metric","dimensions","filters","aggregations","ordering","limit","relationship_variable","time_grain","confidence","reasoning"]
                          }
                        }
                      },
                      "required": ["execution_mode","investigation_direction","intent","metric","secondary_metric","dimensions","filters","aggregations","ordering","limit","relationship_variable","time_grain","confidence","reasoning","alternatives"]
                    }
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid embedded JSON schema", e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
