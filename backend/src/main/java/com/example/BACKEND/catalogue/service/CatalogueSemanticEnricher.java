package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Runs once at catalogue approval time.
 *
 * For each table in the catalogue, sends ONE LLM call that classifies
 * every column with four semantic fields used by the agentic analysis engine:
 *
 *   aggregationMethod  — SUM | COUNT | AVG | LAST_VALUE | NONE
 *   businessMeaning    — plain-English description of what the column measures
 *   comparisonPeriod   — WoW | MoM | YoY | NONE
 *   dateGranularity    — daily | weekly | monthly | event | N/A
 *
 * These fields are then stored on CatalogueColumnEntity and serialised
 * into the catalogue snapshot JSON, so the agent can read them at
 * query time without any extra LLM calls.
 */
@Service
public class CatalogueSemanticEnricher {

    private final OpenAiClient  openAiClient;
    private final ObjectMapper  objectMapper;

    public CatalogueSemanticEnricher(OpenAiClient openAiClient, ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enrich all tables in the catalogue.
     * Silently skips tables that fail — enrichment is best-effort.
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

    // ── Per-table enrichment ──────────────────────────────────────────────────

    private void enrichTable(CatalogueTableEntity table) {
        if (table.getColumns() == null || table.getColumns().isEmpty()) return;

        String systemPrompt = buildSystemPrompt();
        String userPrompt   = buildUserPrompt(table);

        String llmResponse  = openAiClient.chat(systemPrompt, userPrompt);
        applyEnrichment(table, llmResponse);
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are a senior data analyst classifying database columns for business intelligence.
                
                For each column you will return four classification fields:
                
                1. aggregationMethod — how to aggregate this column for KPI/trend analysis:
                   SUM         → totals (revenue, units sold, total spend)
                   COUNT       → counting events or records (order count, user count)
                   AVG         → averages/rates (price, score, rate, ratio)
                   LAST_VALUE  → point-in-time snapshots (account balance, stock price at close)
                   NONE        → not a measurable metric (date, ID, category, name, flag)
                
                2. businessMeaning — one clear sentence describing what this column represents
                   in a business context (not just "the column stores X").
                
                3. comparisonPeriod — the most natural time period to compare this metric over:
                   WoW  → week-over-week (operational metrics like daily sales, signups)
                   MoM  → month-over-month (financial metrics like revenue, MRR)
                   YoY  → year-over-year (annual metrics like yearly growth, ARR)
                   NONE → not a time-series metric, or is a date/dimension column
                
                4. dateGranularity — ONLY for date/time/timestamp columns:
                   daily   → one row per day
                   weekly  → one row per week
                   monthly → one row per month
                   event   → one row per event/transaction (could be multiple per day)
                   N/A     → not a date column
                
                Return ONLY a valid JSON object with a single key "columns" whose value is an array.
                Example: {"columns":[{"columnName":"revenue","aggregationMethod":"SUM","businessMeaning":"Total transaction revenue for the period","comparisonPeriod":"MoM","dateGranularity":"N/A"}]}
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
        sb.append("\nCOLUMNS:\n");

        for (CatalogueColumnEntity col : table.getColumns()) {
            sb.append("- ").append(col.getColumnName())
              .append(" (").append(col.getDataType() != null ? col.getDataType() : "unknown").append(")");

            if (notBlank(col.getDescription())) {
                sb.append(" | description: ").append(col.getDescription());
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

        sb.append("\nClassify every column listed above. Return JSON object: {\"columns\":[...]} with one entry per column.");
        return sb.toString();
    }

    // ── Apply LLM results back to entities ───────────────────────────────────

    private void applyEnrichment(CatalogueTableEntity table, String llmResponse) {
        try {
            String json = stripMarkdown(llmResponse);
            JsonNode root = objectMapper.readTree(json);

            // Accept {"columns":[...]} wrapper (json_object mode) or a bare array
            JsonNode arr = root.isArray() ? root : root.path("columns");

            if (!arr.isArray()) {
                System.out.println("[SemanticEnricher] Unexpected response format for table '"
                        + table.getTableName() + "': " + root);
                return;
            }

            for (JsonNode item : arr) {
                String colName = item.path("columnName").asText("");
                if (colName.isBlank()) continue;

                // Find the matching column entity (case-insensitive)
                CatalogueColumnEntity col = table.getColumns().stream()
                        .filter(c -> c.getColumnName().equalsIgnoreCase(colName))
                        .findFirst()
                        .orElse(null);
                if (col == null) continue;

                String aggMethod      = validated(item.path("aggregationMethod").asText(""),
                                                  "SUM", "COUNT", "AVG", "LAST_VALUE", "NONE");
                String businessMeaning = item.path("businessMeaning").asText("");
                String compPeriod     = validated(item.path("comparisonPeriod").asText(""),
                                                  "WoW", "MoM", "YoY", "NONE");
                String dateGran       = validated(item.path("dateGranularity").asText(""),
                                                  "daily", "weekly", "monthly", "event", "N/A");

                col.setAggregationMethod(aggMethod.isBlank()     ? "NONE"  : aggMethod);
                col.setBusinessMeaning(businessMeaning.isBlank() ? null    : businessMeaning);
                col.setComparisonPeriod(compPeriod.isBlank()     ? "NONE"  : compPeriod);
                col.setDateGranularity(dateGran.isBlank()        ? "N/A"   : dateGran);
                col.setEnriched(true);
            }

            System.out.println("[SemanticEnricher] Enriched table '" + table.getTableName()
                    + "' (" + arr.size() + " columns)");

        } catch (Exception e) {
            System.out.println("[SemanticEnricher] Failed to parse enrichment for table '"
                    + table.getTableName() + "': " + e.getMessage());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String stripMarkdown(String s) {
        if (s == null) return "[]";
        s = s.trim();
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            int end   = s.lastIndexOf("```");
            if (start >= 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }
        return s;
    }

    /** Returns the value only if it matches one of the allowed values; otherwise "". */
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

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
