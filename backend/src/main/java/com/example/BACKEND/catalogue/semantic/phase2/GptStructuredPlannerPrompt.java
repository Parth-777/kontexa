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
                
                For nested warehouse schemas, always bind the full leaf path exactly as listed in the
                catalogue (for example trafficSource.source or geoNetwork.country). Never bind RECORD,
                STRUCT, or OBJECT parent fields as dimensions.
                
                =========================================================
                METRIC SELECTION
                =========================================================
                
                Only include metrics that are required to answer the user's question.
                
                Do not include additional metrics because they appear related or potentially useful.
                
                Every selected metric must have a direct analytical purpose.
                
                Prefer the simplest semantic plan that completely answers the business question.
                
                CRITICAL — semantic identifiers, not SQL:
                
                - metric, secondary_metric, relationship_variable, dimensions[], filters[].column,
                  ordering.column, and every investigation measure/dimension/x/y/column/time_column
                  MUST be an exact catalogue leaf path copied from the CATALOGUE list
                  (for example totals.visits or trafficSource.source).
                
                - NEVER wrap a metric in SQL (forbidden: SUM(...), COUNT(...), AVG(...)).
                
                - NEVER qualify a leaf with a table or dataset name
                  (forbidden: ga_sessions_20160801.totals.visits).
                
                - Aggregation belongs ONLY in aggregations.primary / aggregations.secondary
                  (and investigation measure.aggregation). Never inside the metric string.
                
                Correct:
                  "metric": "totals.visits",
                  "aggregations": { "primary": "SUM", "secondary": null }
                
                Incorrect:
                  "metric": "SUM(ga_sessions_20160801.totals.visits)"
                  "aggregations": { "primary": "SUM(ga_sessions_20160801.totals.visits)", ... }
                
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
                
                ordering.column must be a catalogue leaf path (usually the same leaf as metric),
                never a SQL expression and never a table-qualified name.
                
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
                
                aggregations.primary and aggregations.secondary must be exactly one of:
                SUM, COUNT, AVG, or null.
                
                Never put column paths or SQL text in aggregations.*.
                
                Use the catalogue default_aggregation when present and it is SUM, COUNT, or AVG.
                
                Use SUM for additive business quantities (revenue, cost, count, amount).
                
                Use AVG for rates, averages, and normalized measures.
                
                Use COUNT only when the business question explicitly asks for counts.
                
                Never leave aggregations.primary null when a metric is selected.
                
                =========================================================
                EXECUTION MODE
                =========================================================
                
                Every plan must declare an execution_mode that reflects the user's analytical objective. Decide it from the objective, never from specific words or phrases.
                
                - CANONICAL: the question is answered by a single retrieval/aggregation result. The answer IS that result. Typical: "What is total revenue?", "Show trips by payment type."
                
                - INVESTIGATION: answering requires composing one or more warehouse evidence requests into an EvidencePack (influence, why/contrast, contribution ranking with share, concentration, change over time with supporting context, multi-slice comparison). Do not invent investigation categories — only decide what evidence is required.
                
                Apply this test:
                
                - If one warehouse retrieval is enough AND the answer is that retrieval alone, choose CANONICAL.
                
                - If the answer needs an evidence program (even a single specialized kind such as correlation, concentration, or time_series, or multiple kinds together), choose INVESTIGATION and populate investigation_plan.
                
                Prefer INVESTIGATION when the question asks how X influences Y, why A differs from B, who contributes most, whether value is concentrated, how a metric changed over time, or which segments generate the most — these need evidence_requests, not a lone canonical retrieval.
                
                When execution_mode is INVESTIGATION:
                - populate investigation_plan (required) — see INVESTIGATION PLAN section
                - set investigation_objective to null (legacy compatibility field)
                - set investigation_direction to INCREASE, DECREASE, or ANY when temporal direction applies; otherwise null
                
                When execution_mode is CANONICAL, set investigation_plan and investigation_objective to null.
                
                execution_mode does not change how you select metrics, dimensions, filters, or aggregations for the canonical plan fields. Continue to resolve those exactly as for any other question.
                
                =========================================================
                INVESTIGATION PLAN (required when execution_mode is INVESTIGATION)
                =========================================================
                
                investigation_plan is the sole contract for the investigation runtime.
                Ask: what warehouse evidence is required to answer, and what argument
                structure does that evidence serve?
                
                The runtime executes ONLY evidence_requests (SQL kinds).
                hypotheses, evidence_purpose, and insufficiency are planner metadata
                for reasoning fidelity — they do not change SQL compilation.
                
                Structure:
                - phenomenon: { subject, measure }
                - scope: { filters: [...] } — shared scope for all requests
                - hypotheses: [{ id, claim, status }] — may be []
                - evidence_requests: [{ id, kind, role?, hypothesis_id?, evidence_purpose?, measure?, dimension?, x?, y?, column?, limit?, time_column?, time_grain?, side?, slice?, focal_slice?, baseline_slice?, params? }]
                - insufficiency: { answerable, unknowns[], blocked_reasons[] }
                - semantic_confidence: 0.0–1.0
                
                Each constraint in a slice (when a request has slice/focal_slice/baseline_slice) is exactly one of:
                - { filter: { column, operator, value } }
                - { time_range: { column, start, end } } — half-open [start, end)
                - { rank: { column, side (LOWER|UPPER), fraction, grain (optional) } }
                - { population: true } — the scoped universe with no additional bound
                
                Supported evidence_requests.kind values (warehouse retrieval primitives ONLY):
                - slice_metric
                - metric_by_dimension
                - distribution
                - correlation
                - rank
                - top_n
                - bottom_n
                - share
                - concentration
                - time_series
                
                Compose any mixture of these kinds in one plan. Do not emit investigation categories, investigation types, playbooks, or question-type labels.
                
                ---------------------------------------------------------
                HYPOTHESES (for WHY / EXPLAIN / INVESTIGATE)
                ---------------------------------------------------------
                When the question asks why / explain / investigate a difference or pattern:
                - Emit 2–5 competing hypotheses grounded in catalogue-available concepts.
                - Each hypothesis.id is stable (H1, H2, …).
                - claim is a testable statement (not a question category label).
                - status:
                  - PRIMARY — leading candidate before execution
                  - ALTERNATIVE — competing candidate
                  - REJECTED_CANDIDATE — considered but not testable with this catalogue
                - Do NOT invent metrics/dimensions absent from the catalogue.
                - If the question is a single retrieval shape (e. g. pure concentration), hypotheses may be [].
                
                ---------------------------------------------------------
                EVIDENCE PURPOSE (why each request exists)
                ---------------------------------------------------------
                Every evidence_request SHOULD set evidence_purpose:
                - PRIMARY — directly tests a hypothesis claim
                - VALIDATION — corroborates a PRIMARY result with a related kind
                - COMPARISON — establishes the phenomenon gap (e.g. FOCAL vs BASELINE)
                - CONTRADICTION — evidence that would weaken/falsify a hypothesis
                - CONTEXT — background distribution / scope without deciding the claim
                
                Link requests to hypotheses via hypothesis_id when they test a claim.
                COMPARISON / CONTEXT may use hypothesis_id = null.
                
                role FOCAL and BASELINE remains for paired slice contrasts.
                evidence_purpose is orthogonal to role:
                  FOCAL/BASELINE slice_metric pair → usually evidence_purpose=COMPARISON
                
                ---------------------------------------------------------
                INSUFFICIENCY (planner honesty BEFORE execution)
                ---------------------------------------------------------
                Declare what the catalogue cannot support:
                - answerable=true — evidence_requests are sufficient for a grounded answer
                  (unknowns may still list partial gaps)
                - answerable=false — cannot close the causal/explanatory claim;
                  still emit any executable requests that bound the problem;
                  list unknowns and blocked_reasons honestly
                
                unknowns / blocked_reasons must be catalogue-grounded
                (e.g. "marketing spend unavailable"), never invented warehouse facts.
                When fully answerable with no gaps, use:
                  { "answerable": true, "unknowns": [], "blocked_reasons": [] }
                
                Evidence composition guidance (kinds only — not categories):
                - Influence / relationship (e.g. how trip distance influences total amount): ALWAYS emit at least correlation plus one of distribution or metric_by_dimension (prefer both). Never emit correlation alone.
                - Why A vs B (e.g. payment type 1 tips more than type 2): emit two slice_metric requests with role FOCAL and BASELINE (filters on each request's slice only). Prefer slice_metric over metric_by_dimension for paired contrasts. Leave focal_slice/baseline_slice empty on metric_by_dimension unless you intentionally need contrast columns in one query. For WHY/EXPLAIN also emit hypotheses + PRIMARY/VALIDATION/CONTRADICTION evidence linked by hypothesis_id.
                - Who contributes most: emit top_n or rank AND share together.
                - Change over time: emit time_series (set time_column + time_grain). Add supporting slice_metric if helpful.
                - Which segments/regions generate most: ALWAYS INVESTIGATION — emit rank or top_n (dimension=region or segment column). Do not use CANONICAL for "which X generate highest Y".
                - Is value concentrated: ALWAYS emit concentration (kind=concentration). Optionally add share or top_n alongside — never substitute top_n alone for concentration questions.
                
                Concrete evidence programs (illustrative — adapt columns to catalogue):
                - "How does trip distance influence total amount?" → INVESTIGATION: correlation(x=trip_distance,y=total_amount) + distribution(dimension=trip_distance) + metric_by_dimension(dimension=payment_type or similar, measure=total_amount)
                - "Why do payment type 1 users tip more than payment type 2?" → INVESTIGATION: hypotheses + slice_metric FOCAL/BASELINE (COMPARISON) + supporting PRIMARY/VALIDATION requests linked to hypotheses
                - "Why is desktop traffic higher than mobile?" → INVESTIGATION: competing hypotheses (e.g. organic favors desktop; direct dominates; engagement) + COMPARISON FOCAL/BASELINE by device + PRIMARY/VALIDATION/CONTRADICTION requests per testable hypothesis; declare unknowns such as marketing spend if absent
                - "Which customers contribute most revenue?" → INVESTIGATION: top_n(customer_id,total_amount) + share(customer_id,total_amount)
                - "How has revenue changed over time?" → INVESTIGATION: time_series(total_amount, time_column, MONTH)
                - "Which regions generate the highest revenue?" → INVESTIGATION: rank or top_n(region,total_amount)
                - "Is revenue concentrated among a few customers?" → INVESTIGATION: concentration(customer_id,total_amount) [+ optional share]
                
                Use role FOCAL and BASELINE on paired slice_metric requests when contrasting two slices.
                For correlation set x and y (or dimension + measure).
                For time_series set time_column and time_grain (DAY|WEEK|MONTH|QUARTER|YEAR).
                For rank/top_n/bottom_n/share/concentration set dimension, measure, and optional limit.
                For metric_by_dimension set dimension + measure; do not put contrast filters into focal_slice/baseline_slice unless both sides are required in one SQL.
                Prefer empty slice constraints over inventing IS NOT NULL filters.
                Prefer catalogue columns that exist; for location/region questions use the best available location dimension (for example PULocationID when region is absent). Do not invent customer_id when the catalogue has no customer identifier — use the closest entity dimension present, or lower confidence.
                
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
                
                Do not embed SQL fragments inside JSON string fields.
                
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
            if (catalogue.isContainer(c.columnName())) {
                continue;
            }
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
                            "primary": {
                              "type": ["string","null"],
                              "enum": ["SUM","COUNT","AVG",null]
                            },
                            "secondary": {
                              "type": ["string","null"],
                              "enum": ["SUM","COUNT","AVG",null]
                            }
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
                        "investigation_plan": {
                          "type": ["object","null"],
                          "additionalProperties": false,
                          "properties": {
                            "phenomenon": {
                              "type": "object",
                              "additionalProperties": false,
                              "properties": {
                                "subject": { "type": ["string","null"] },
                                "measure": {
                                  "type": ["object","null"],
                                  "additionalProperties": false,
                                  "properties": {
                                    "column": { "type": "string" },
                                    "aggregation": { "type": "string", "enum": ["SUM","COUNT","AVG"] },
                                    "grain": { "type": "array", "items": { "type": "string" } }
                                  },
                                  "required": ["column","aggregation","grain"]
                                }
                              },
                              "required": ["subject","measure"]
                            },
                            "scope": {
                              "type": "object",
                              "additionalProperties": false,
                              "properties": {
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
                                }
                              },
                              "required": ["filters"]
                            },
                            "hypotheses": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "additionalProperties": false,
                                "properties": {
                                  "id": { "type": "string" },
                                  "claim": { "type": "string" },
                                  "status": {
                                    "type": "string",
                                    "enum": ["PRIMARY","ALTERNATIVE","REJECTED_CANDIDATE"]
                                  }
                                },
                                "required": ["id","claim","status"]
                              }
                            },
                            "evidence_requests": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "additionalProperties": false,
                                "properties": {
                                  "id": { "type": "string" },
                                  "kind": { "type": "string" },
                                  "role": { "type": ["string","null"] },
                                  "hypothesis_id": { "type": ["string","null"] },
                                  "evidence_purpose": {
                                    "type": ["string","null"],
                                    "enum": ["PRIMARY","VALIDATION","COMPARISON","CONTRADICTION","CONTEXT",null]
                                  },
                                  "measure": {
                                    "type": ["object","null"],
                                    "additionalProperties": false,
                                    "properties": {
                                      "column": { "type": "string" },
                                      "aggregation": { "type": "string", "enum": ["SUM","COUNT","AVG"] },
                                      "grain": { "type": "array", "items": { "type": "string" } }
                                    },
                                    "required": ["column","aggregation","grain"]
                                  },
                                  "dimension": { "type": ["string","null"] },
                                  "x": { "type": ["string","null"] },
                                  "y": { "type": ["string","null"] },
                                  "column": { "type": ["string","null"] },
                                  "limit": { "type": ["integer","null"] },
                                  "time_column": { "type": ["string","null"] },
                                  "time_grain": { "type": ["string","null"], "enum": ["DAY","WEEK","MONTH","QUARTER","YEAR",null] },
                                  "side": { "type": ["string","null"], "enum": ["LOWER","UPPER",null] },
                                  "slice": { "$ref": "#/$defs/investigation_slice" },
                                  "focal_slice": { "$ref": "#/$defs/investigation_slice" },
                                  "baseline_slice": { "$ref": "#/$defs/investigation_slice" },
                                  "params": {
                                    "type": ["object","null"],
                                    "additionalProperties": false,
                                    "properties": {
                                      "limit": { "type": ["integer","null"] },
                                      "time_column": { "type": ["string","null"] },
                                      "time_grain": { "type": ["string","null"] },
                                      "side": { "type": ["string","null"] }
                                    },
                                    "required": ["limit","time_column","time_grain","side"]
                                  }
                                },
                                "required": ["id","kind","role","hypothesis_id","evidence_purpose","measure","dimension","x","y","column","limit","time_column","time_grain","side","slice","focal_slice","baseline_slice","params"]
                              }
                            },
                            "insufficiency": {
                              "type": ["object","null"],
                              "additionalProperties": false,
                              "properties": {
                                "answerable": { "type": "boolean" },
                                "unknowns": {
                                  "type": "array",
                                  "items": { "type": "string" }
                                },
                                "blocked_reasons": {
                                  "type": "array",
                                  "items": { "type": "string" }
                                }
                              },
                              "required": ["answerable","unknowns","blocked_reasons"]
                            },
                            "semantic_confidence": { "type": "number" }
                          },
                          "required": ["phenomenon","scope","hypotheses","evidence_requests","insufficiency","semantic_confidence"]
                        },
                        "investigation_objective": {
                          "type": ["object","null"],
                          "additionalProperties": false,
                          "properties": {
                            "measure": {
                              "type": "object",
                              "additionalProperties": false,
                              "properties": {
                                "column": { "type": "string" },
                                "aggregation": { "type": "string", "enum": ["SUM","COUNT","AVG"] },
                                "grain": { "type": "array", "items": { "type": "string" } }
                              },
                              "required": ["column","aggregation","grain"]
                            },
                            "scope": {
                              "type": "object",
                              "additionalProperties": false,
                              "properties": {
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
                                }
                              },
                              "required": ["filters"]
                            },
                            "comparison": {
                              "type": "object",
                              "additionalProperties": false,
                              "properties": {
                                "focal": { "$ref": "#/$defs/investigation_slice" },
                                "baseline": { "$ref": "#/$defs/investigation_slice" }
                              },
                              "required": ["focal","baseline"]
                            },
                            "decomposition": {
                              "type": "object",
                              "additionalProperties": false,
                              "properties": {
                                "dimensions": { "type": "array", "items": { "type": "string" } },
                                "exclude": { "type": "array", "items": { "type": "string" } }
                              },
                              "required": ["dimensions","exclude"]
                            },
                            "salience": {
                              "type": "string",
                              "enum": ["FOCAL_HIGHER","FOCAL_LOWER","DIFFERENCE"]
                            },
                            "semantic_confidence": { "type": "number" }
                          },
                          "required": ["measure","scope","comparison","decomposition","salience","semantic_confidence"]
                        },
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
                                  "primary": {
                                    "type": ["string","null"],
                                    "enum": ["SUM","COUNT","AVG",null]
                                  },
                                  "secondary": {
                                    "type": ["string","null"],
                                    "enum": ["SUM","COUNT","AVG",null]
                                  }
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
                              "time_grain": { "type": ["string","null"] },
                              "confidence": { "type": "number" },
                              "reasoning": { "type": "string" }
                            },
                            "required": ["intent","metric","secondary_metric","dimensions","filters","aggregations","ordering","limit","relationship_variable","time_grain","confidence","reasoning"]
                          }
                        }
                      },
                      "required": ["execution_mode","investigation_direction","intent","metric","secondary_metric","dimensions","filters","aggregations","ordering","limit","relationship_variable","time_grain","confidence","reasoning","investigation_plan","investigation_objective","alternatives"],
                      "$defs": {
                        "investigation_slice": {
                          "type": "object",
                          "additionalProperties": false,
                          "properties": {
                            "constraints": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "additionalProperties": false,
                                "properties": {
                                  "filter": {
                                    "type": ["object","null"],
                                    "additionalProperties": false,
                                    "properties": {
                                      "column": { "type": "string" },
                                      "operator": { "type": "string" },
                                      "value": { "type": "string" }
                                    },
                                    "required": ["column","operator","value"]
                                  },
                                  "time_range": {
                                    "type": ["object","null"],
                                    "additionalProperties": false,
                                    "properties": {
                                      "column": { "type": "string" },
                                      "start": { "type": "string" },
                                      "end": { "type": "string" }
                                    },
                                    "required": ["column","start","end"]
                                  },
                                  "rank": {
                                    "type": ["object","null"],
                                    "additionalProperties": false,
                                    "properties": {
                                      "column": { "type": "string" },
                                      "side": { "type": "string", "enum": ["LOWER","UPPER"] },
                                      "fraction": { "type": "number" },
                                      "grain": { "type": "array", "items": { "type": "string" } }
                                    },
                                    "required": ["column","side","fraction","grain"]
                                  },
                                  "population": { "type": ["boolean","null"] }
                                },
                                "required": ["filter","time_range","rank","population"]
                              }
                            }
                          },
                          "required": ["constraints"]
                        }
                      }
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
