package com.example.BACKEND.catalogue.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads an approved catalogue snapshot and scores every column as either:
 *   METRIC     — numeric column likely representing a measurable business value
 *   DATE       — temporal column used for time-series analysis
 *   DIMENSION  — categorical column used for grouping / segmentation
 *
 * No SQL or LLM calls here — pure catalogue metadata scoring.
 */
@Service
public class KpiDetectorService {

    // ── Keyword sets ──────────────────────────────────────────────────────────

    private static final Set<String> METRIC_KEYWORDS = Set.of(
            "revenue", "sales", "amount", "price", "cost", "profit", "income",
            "count", "total", "sum", "qty", "quantity", "units", "orders",
            "users", "sessions", "views", "clicks", "visits", "transactions",
            "rate", "conversion", "churn", "retention", "score", "value",
            "open", "close", "high", "low", "volume", "spend", "budget",
            "impressions", "leads", "signups", "installs", "downloads",
            "mrr", "arr", "ltv", "arpu", "cac", "nps", "dau", "mau"
    );

    private static final Set<String> DATE_KEYWORDS = Set.of(
            "date", "time", "day", "week", "month", "year",
            "created", "updated", "modified", "timestamp", "at", "on",
            "start", "end", "period", "recorded", "logged", "occurred"
    );

    private static final Set<String> DIMENSION_KEYWORDS = Set.of(
            "region", "country", "city", "state", "zone", "location", "geo",
            "category", "type", "status", "platform", "channel", "medium",
            "segment", "group", "class", "tier", "level", "plan",
            "product", "brand", "source", "campaign", "version", "cohort",
            "device", "os", "browser", "market", "vertical", "industry"
    );

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int", "integer", "bigint", "smallint", "tinyint",
            "float", "float64", "double", "real",
            "numeric", "decimal", "number", "bignumeric", "fixed"
    );

    private static final Set<String> DATE_TYPES = Set.of(
            "date", "datetime", "timestamp", "timestamp_ntz", "timestamp_ltz",
            "timestamptz", "time"
    );

    private static final Set<String> STRING_TYPES = Set.of(
            "string", "text", "varchar", "char", "nvarchar", "clob"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Lightweight column classifier used by the universal sampling loop.
     * Returns column-type hints for a single table node — no gating, no thresholds.
     * Every table is considered worth sampling.
     */
    public ColumnHints classifyColumns(JsonNode tableNode) {
        String tableName   = tableNode.path("tableName").asText("");
        String tableSchema = tableNode.path("tableSchema").asText("public");

        String       dateCol    = null;
        List<String> stringCols = new ArrayList<>();
        List<String> numericCols = new ArrayList<>();

        for (JsonNode col : tableNode.path("columns")) {
            String name     = col.path("columnName").asText("");
            String type     = col.path("dataType").asText("").toLowerCase();
            String role     = col.path("role").asText("").toLowerCase();
            String dateGran = col.path("dateGranularity").asText("N/A");
            String aggMethod = col.path("aggregationMethod").asText("NONE");
            if (name.isBlank()) continue;

            if (classifyAsDate(name.toLowerCase(), type, role, dateGran)) {
                if (dateCol == null) dateCol = name;
            } else if (classifyAsMetric(name.toLowerCase(), type, role, aggMethod)) {
                numericCols.add(name);
            } else {
                // Everything else is potentially a categorical/string column
                stringCols.add(name);
            }
        }

        return new ColumnHints(tableName, tableSchema, dateCol, stringCols, numericCols);
    }

    /**
     * Scan the catalogue snapshot and return a DetectedSchema describing
     * every table's metric, date, and dimension columns.
     * Kept for backward compatibility — not used as a gate in the analysis pipeline.
     */
    public DetectedSchema detect(JsonNode catalogueNode) {
        List<DetectedTable> tables = new ArrayList<>();

        for (JsonNode tableNode : catalogueNode.path("tables")) {
            String tableName   = tableNode.path("tableName").asText("");
            String tableSchema = tableNode.path("tableSchema").asText("public");
            if (tableName.isBlank()) continue;

            List<String> metrics    = new ArrayList<>();
            List<String> dates      = new ArrayList<>();
            List<String> dimensions = new ArrayList<>();

            for (JsonNode col : tableNode.path("columns")) {
                String colName  = col.path("columnName").asText("");
                String dataType = col.path("dataType").asText("").toLowerCase();
                String role     = col.path("role").asText("").toLowerCase();
                String desc     = col.path("description").asText("").toLowerCase();
                // Semantic enrichment signals (present after catalogue approval + LLM enrichment)
                String aggMethod  = col.path("aggregationMethod").asText("NONE");
                String dateGran   = col.path("dateGranularity").asText("N/A");
                if (colName.isBlank()) continue;

                String nameLower = colName.toLowerCase();

                if (classifyAsMetric(nameLower, dataType, role, aggMethod)) {
                    metrics.add(colName);
                } else if (classifyAsDate(nameLower, dataType, role, dateGran)) {
                    dates.add(colName);
                } else if (classifyAsDimension(nameLower, dataType, role, desc)) {
                    dimensions.add(colName);
                }
            }

            // Only include tables that have at least one metric or date
            if (!metrics.isEmpty() || !dates.isEmpty()) {
                tables.add(new DetectedTable(tableName, tableSchema, metrics, dates, dimensions));
            }
        }

        return new DetectedSchema(tables);
    }

    // ── Classification helpers ────────────────────────────────────────────────

    private boolean classifyAsMetric(String name, String type, String role, String aggMethod) {
        // LLM-enriched signal takes priority
        if ("SUM".equals(aggMethod) || "COUNT".equals(aggMethod)
                || "AVG".equals(aggMethod) || "LAST_VALUE".equals(aggMethod)) return true;
        // Legacy keyword/role matching
        if ("metric".equals(role) || "measure".equals(role) || "kpi".equals(role)) return true;
        if (!isNumericType(type)) return false;
        return METRIC_KEYWORDS.stream().anyMatch(name::contains);
    }

    private boolean classifyAsDate(String name, String type, String role, String dateGran) {
        // LLM-enriched signal takes priority
        if (dateGran != null && !"N/A".equals(dateGran) && !dateGran.isBlank()) return true;
        // Legacy keyword/role matching
        if ("date".equals(role) || "time".equals(role) || "timestamp".equals(role)) return true;
        if (isDateType(type)) return true;
        return DATE_KEYWORDS.stream().anyMatch(name::contains);
    }

    private boolean classifyAsDimension(String name, String type, String role, String desc) {
        if ("dimension".equals(role) || "segment".equals(role)) return true;
        if (!isStringType(type)) return false;
        return DIMENSION_KEYWORDS.stream().anyMatch(name::contains)
                || DIMENSION_KEYWORDS.stream().anyMatch(desc::contains);
    }

    private boolean isNumericType(String type) {
        return NUMERIC_TYPES.stream().anyMatch(type::contains);
    }

    private boolean isDateType(String type) {
        return DATE_TYPES.stream().anyMatch(type::contains);
    }

    private boolean isStringType(String type) {
        return STRING_TYPES.stream().anyMatch(type::contains);
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    /**
     * Lightweight column classification result for one table.
     * Used by the universal sampling loop in AgentAnalysisService.
     */
    public record ColumnHints(
            String tableName,
            String tableSchema,
            String dateCol,           // first date/timestamp column (null if none)
            List<String> stringCols,  // categorical / text columns
            List<String> numericCols  // numeric / metric columns
    ) {}

    public record DetectedTable(
            String tableName,
            String tableSchema,
            List<String> metricColumns,
            List<String> dateColumns,
            List<String> dimensionColumns
    ) {
        /**
         * Returns true when this table has enough information for useful analysis.
         * Tables with only dimension columns (e.g. all-string catalogues like Netflix)
         * are still analysable via COUNT(*) queries.
         */
        public boolean isAnalysable() {
            return !metricColumns.isEmpty() || !dimensionColumns.isEmpty();
        }

        /** True only when there are real numeric metric columns to aggregate. */
        public boolean hasNumericMetrics() {
            return !metricColumns.isEmpty();
        }
    }

    public record DetectedSchema(List<DetectedTable> tables) {
        public boolean hasAnalysableData() {
            return tables.stream().anyMatch(DetectedTable::isAnalysable);
        }

        /** Tables that have at least one metric column. */
        public List<DetectedTable> analysableTables() {
            return tables.stream().filter(DetectedTable::isAnalysable).toList();
        }
    }
}
