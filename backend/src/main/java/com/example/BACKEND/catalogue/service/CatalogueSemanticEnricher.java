package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Runs once at catalogue approval time.
 *
 * Complements generate-time enrichment ({@link CatalogueEnrichmentService}):
 *   - Always fills / refreshes compact analytical guards:
 *       aggregationMethod, comparisonPeriod, dateGranularity
 *   - Preserves rich {@code businessMeaning} already produced at generate time
 *     (single semantic source of truth). Only fills businessMeaning when blank
 *     or clearly too short to be a curriculum explanation.
 *
 * Results are stored on {@link CatalogueColumnEntity} and serialized into the
 * catalogue snapshot for query-time agents.
 */
@Service
public class CatalogueSemanticEnricher {

    /** Below this length, approve-time may fill a missing/weak businessMeaning. */
    private static final int RICH_BUSINESS_MEANING_MIN_CHARS = 120;

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    public CatalogueSemanticEnricher(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Enrich all tables in the catalogue. Best-effort per table.
     */
    public void enrich(ClientCatalogueEntity catalogue) {
        for (CatalogueTableEntity table : catalogue.getTables()) {
            try {
                enrichTable(table);
            } catch (Exception e) {
                System.out.println("[SemanticEnricher] Skipped table '"
                        + table.getTableName() + "': " + e.getMessage());
            }
        }
    }

    private void enrichTable(CatalogueTableEntity table) {
        if (table.getColumns() == null || table.getColumns().isEmpty()) return;

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(table);

        String llmResponse = openAiClient.chat(systemPrompt, userPrompt, 8192);
        applyEnrichment(table, llmResponse);
    }

    private String buildSystemPrompt() {
        return """
                You are a senior data analyst classifying database columns for business intelligence.
                
                Generate-time enrichment may already provide a RICH businessMeaning (long semantic
                explanation). That text is the authoritative teaching material for future AI agents.
                Your job at approval time is primarily to classify compact analytical guards.
                
                For each column return:
                
                1. aggregationMethod — how to aggregate this column for KPI/trend analysis:
                   SUM            → totals (revenue, units, spend, bounce flags when summing events)
                   COUNT          → counting rows/events
                   COUNT_DISTINCT → unique counts (users, sessions when counting distinct ids)
                   AVG            → averages/rates (price, score, ratio)
                   MIN | MAX      → extrema
                   LAST_VALUE     → point-in-time snapshots (balance, closing price)
                   NONE           → not a measurable metric (date, ID, category, name)
                
                2. businessMeaning — ONLY if the input has no usable rich meaning yet.
                   If the column already has a long businessMeaning in the input, return it unchanged
                   (or omit / leave empty — the system will keep the existing text).
                   If missing or trivially short, write a rich coherent semantic explanation covering
                   what it stores, business concept, when to use / not use, aggregation, grain, caveats,
                   distinctions from confused siblings, units/format if material, and analytical
                   interpretation for binary flags. Write naturally — not a rigid "Use for..." template.
                
                3. comparisonPeriod — most natural comparison window:
                   WoW | MoM | YoY | NONE
                
                4. dateGranularity — ONLY for date/time columns:
                   daily | weekly | monthly | event | N/A
                
                5. OPTIONAL machine-readable metric semantics (only when you are confident):
                   displayUnit — currency | percent | count | number | raw
                   scaleToCanonical — multiply raw warehouse value by this to get canonical units
                     (e.g. if stored as micros of currency, use 1e-6; if already canonical, use 1)
                   grainLabel — conservative entity name for COUNT language (sessions, rows, records)
                   Omit these fields when uncertain. Never invent scale.
                
                Return ONLY valid JSON: {"columns":[...]}
                Each element: columnName, aggregationMethod, businessMeaning, comparisonPeriod,
                dateGranularity, and optional displayUnit, scaleToCanonical, grainLabel.
                """;
    }

    private String buildUserPrompt(CatalogueTableEntity table) {
        StringBuilder sb = new StringBuilder();
        sb.append("TABLE: ").append(table.getTableName());
        if (table.getTableSchema() != null && !table.getTableSchema().isBlank()) {
            sb.append(" (schema: ").append(table.getTableSchema()).append(")");
        }
        sb.append("\n");
        if (table.getDescription() != null && !table.getDescription().isBlank()) {
            sb.append("Description: ").append(table.getDescription()).append("\n");
        }
        if (table.getRowCount() != null && table.getRowCount() > 0) {
            sb.append("Row count: ").append(table.getRowCount()).append("\n");
        }
        sb.append("\nCOLUMNS (preserve rich businessMeaning when already present):\n");

        for (CatalogueColumnEntity col : table.getColumns()) {
            if (NestedSchemaFieldGraph.ROLE_CONTAINER.equalsIgnoreCase(col.getRole())
                    || "ignore".equalsIgnoreCase(col.getRole())
                    || NestedSchemaFieldGraph.isNestedContainerType(col.getDataType())) {
                continue;
            }
            sb.append("- ").append(col.getColumnName())
              .append(" (").append(col.getDataType() != null ? col.getDataType() : "unknown").append(")");

            if (notBlank(col.getRole())) {
                sb.append(" | role: ").append(col.getRole());
            }
            if (notBlank(col.getDescription())) {
                sb.append(" | shortDescription: ").append(col.getDescription());
            }
            if (notBlank(col.getBusinessMeaning())) {
                sb.append(" | existingBusinessMeaning: ")
                  .append(truncate(col.getBusinessMeaning(), 500));
                if (isRichBusinessMeaning(col.getBusinessMeaning())) {
                    sb.append(" [KEEP — do not shorten]");
                }
            } else {
                sb.append(" | existingBusinessMeaning: (missing — please provide rich meaning)");
            }
            if (notBlank(col.getAggregationMethod())) {
                sb.append(" | existingAggregation: ").append(col.getAggregationMethod());
            }
            if (notBlank(col.getSampleValues()) && !"[]".equals(col.getSampleValues().trim())) {
                String cleaned = col.getSampleValues()
                        .replace("[", "").replace("]", "").replace("\"", "").trim();
                if (!cleaned.isBlank()) {
                    sb.append(" | samples: ").append(truncate(cleaned, 80));
                }
            }
            if (notBlank(col.getMinValue()) || notBlank(col.getMaxValue())) {
                sb.append(" | range: ").append(col.getMinValue()).append(" to ").append(col.getMaxValue());
            }
            sb.append("\n");
        }

        sb.append("\nClassify every column listed above. Return JSON object: {\"columns\":[...]}.");
        return sb.toString();
    }

    private void applyEnrichment(CatalogueTableEntity table, String llmResponse) {
        try {
            String json = stripMarkdown(llmResponse);
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.isArray() ? root : root.path("columns");

            if (!arr.isArray()) {
                System.out.println("[SemanticEnricher] Unexpected response format for table '"
                        + table.getTableName() + "': " + root);
                return;
            }

            int preservedMeaning = 0;
            int filledMeaning = 0;

            for (JsonNode item : arr) {
                String colName = item.path("columnName").asText("");
                if (colName.isBlank()) continue;

                CatalogueColumnEntity col = table.getColumns().stream()
                        .filter(c -> c.getColumnName().equalsIgnoreCase(colName))
                        .findFirst()
                        .orElse(null);
                if (col == null) continue;

                String aggMethod = validated(item.path("aggregationMethod").asText(""),
                        "SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX", "LAST_VALUE", "NONE");
                String businessMeaning = item.path("businessMeaning").asText("");
                String compPeriod = validated(item.path("comparisonPeriod").asText(""),
                        "WoW", "MoM", "YoY", "NONE");
                String dateGran = validated(item.path("dateGranularity").asText(""),
                        "daily", "weekly", "monthly", "event", "N/A");

                if (!aggMethod.isBlank()) {
                    col.setAggregationMethod(aggMethod);
                } else if (!notBlank(col.getAggregationMethod())) {
                    col.setAggregationMethod("NONE");
                }

                col.setComparisonPeriod(compPeriod.isBlank() ? "NONE" : compPeriod);
                col.setDateGranularity(dateGran.isBlank() ? "N/A" : dateGran);

                if (isRichBusinessMeaning(col.getBusinessMeaning())) {
                    // Keep generate-time (or prior) rich semantic SoT — never overwrite with a gloss.
                    preservedMeaning++;
                } else if (isRichBusinessMeaning(businessMeaning)) {
                    col.setBusinessMeaning(businessMeaning.trim());
                    filledMeaning++;
                } else if (!notBlank(col.getBusinessMeaning()) && notBlank(businessMeaning)) {
                    col.setBusinessMeaning(businessMeaning.trim());
                    filledMeaning++;
                }

                applyMetricSemantics(col, item);

                col.setEnriched(true);
            }

            System.out.println("[SemanticEnricher] Enriched table '" + table.getTableName()
                    + "' (" + arr.size() + " columns; preservedBusinessMeaning="
                    + preservedMeaning + ", filledBusinessMeaning=" + filledMeaning + ")");

        } catch (Exception e) {
            System.out.println("[SemanticEnricher] Failed to parse enrichment for table '"
                    + table.getTableName() + "': " + e.getMessage());
        }
    }

    static boolean isRichBusinessMeaning(String text) {
        return text != null && text.trim().length() >= RICH_BUSINESS_MEANING_MIN_CHARS;
    }

    private String stripMarkdown(String s) {
        if (s == null) return "[]";
        s = s.trim();
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (start >= 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }
        return s;
    }

    private String validated(String value, String... allowed) {
        if (value == null || value.isBlank()) return "";
        for (String a : allowed) {
            if (a.equalsIgnoreCase(value.trim())) return a;
        }
        return "";
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private void applyMetricSemantics(CatalogueColumnEntity col, JsonNode item) {
        String displayUnit = item.path("displayUnit").asText("").trim();
        if (!displayUnit.isBlank()) {
            String u = validated(displayUnit, "currency", "percent", "count", "number", "raw", "usd", "money");
            if (!u.isBlank()) col.setDisplayUnit(u.toLowerCase());
        }
        if (item.has("scaleToCanonical") && !item.path("scaleToCanonical").isNull()) {
            try {
                double scale = item.path("scaleToCanonical").asDouble();
                if (!Double.isNaN(scale) && !Double.isInfinite(scale) && scale != 0.0) {
                    col.setScaleToCanonical(scale);
                }
            } catch (Exception ignored) {
                // leave unset
            }
        }
        String grain = item.path("grainLabel").asText("").trim();
        if (!grain.isBlank() && grain.length() <= 40) {
            col.setGrainLabel(grain);
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
