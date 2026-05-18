package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core agentic analysis service.
 *
 * Flow:
 *  1. Load the approved catalogue snapshot for the tenant
 *  2. KpiDetectorService scores every column → DetectedSchema
 *  3. Run targeted SQL queries (trend, breakdown) for each detected metric
 *  4. Feed all collected data to the LLM for synthesis
 *  5. Return a fully populated AgentDashboardResult
 *
 * Queries run against whatever provider the tenant has configured
 * (BigQuery, Snowflake, or PostgreSQL) — using the same connector
 * services already in use by CatalogueQueryService.
 */
@Service
public class AgentAnalysisService {

    private static final int MAX_METRICS_PER_TABLE    = 4;
    private static final int MAX_DIMENSIONS_PER_TABLE = 2;
    private static final int TREND_ROW_LIMIT          = 60;
    private static final int BREAKDOWN_ROW_LIMIT      = 10;

    private final CatalogueApprovalService      approvalService;
    private final KpiDetectorService            kpiDetector;
    private final TenantCloudConnectionService  cloudConnectionService;
    private final BigQueryConnectorService      bigQueryConnectorService;
    private final SnowflakeConnectorService     snowflakeConnectorService;
    private final JdbcTemplate                  jdbcTemplate;
    private final OpenAiClient                  openAiClient;
    private final ObjectMapper                  objectMapper;

    public AgentAnalysisService(
            CatalogueApprovalService     approvalService,
            KpiDetectorService           kpiDetector,
            TenantCloudConnectionService cloudConnectionService,
            BigQueryConnectorService     bigQueryConnectorService,
            SnowflakeConnectorService    snowflakeConnectorService,
            JdbcTemplate                 jdbcTemplate,
            OpenAiClient                 openAiClient,
            ObjectMapper                 objectMapper
    ) {
        this.approvalService          = approvalService;
        this.kpiDetector              = kpiDetector;
        this.cloudConnectionService   = cloudConnectionService;
        this.bigQueryConnectorService = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.jdbcTemplate             = jdbcTemplate;
        this.openAiClient             = openAiClient;
        this.objectMapper             = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    public AgentDashboardResult analyse(String clientId) {
        AgentDashboardResult result = new AgentDashboardResult();
        result.setLastUpdated(Instant.now().toString());

        try {
            // 1. Load catalogue
            String snapshotJson = approvalService.getApprovedSnapshot(clientId);
            JsonNode catalogueNode = objectMapper.readTree(snapshotJson);

            // 2. Detect provider + config
            String provider = cloudConnectionService.getProvider(clientId);
            Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg  = Optional.empty();
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg  = Optional.empty();

            if ("bigquery".equals(provider)) {
                bqCfg  = cloudConnectionService.getBigQueryConfig(clientId);
            } else if ("snowflake".equals(provider)) {
                sfCfg  = cloudConnectionService.getSnowflakeConfig(clientId);
            }

            boolean useBigQuery  = bqCfg.isPresent()  && notBlank(bqCfg.get().projectId());
            boolean useSnowflake = sfCfg.isPresent()   && notBlank(sfCfg.get().account());

            result.setDataSource(useBigQuery ? "BigQuery" : useSnowflake ? "Snowflake" : "PostgreSQL");

            // 3. Detect schema
            KpiDetectorService.DetectedSchema schema = kpiDetector.detect(catalogueNode);

            if (!schema.hasAnalysableData()) {
                result.setErrorMessage("No metric columns detected in the approved catalogue. "
                        + "Ensure your catalogue has numeric columns and is approved.");
                result.setConfidence(0);
                result.setKpiCards(List.of());
                result.setInsights(List.of());
                result.setInvestigations(List.of());
                result.setAnomalies(List.of());
                result.setFollowUpQuestions(List.of());
                return result;
            }

            // 4. Collect query results
            final Optional<TenantCloudConnectionService.BigQueryConfig>  bqFinal = bqCfg;
            final Optional<TenantCloudConnectionService.SnowflakeConfig> sfFinal = sfCfg;
            final boolean useBQ = useBigQuery;
            final boolean useSF = useSnowflake;

            List<CollectedData> collected = new ArrayList<>();
            List<String> tablesUsed = new ArrayList<>();

            for (KpiDetectorService.DetectedTable table : schema.analysableTables()) {
                tablesUsed.add(table.tableName());
                String tableRef = buildTableRef(table, provider);

                // Extract enriched semantic metadata for this table's columns
                Map<String, EnrichedColInfo> enriched = buildEnrichedMap(catalogueNode, table.tableName());

                List<String> metrics    = limit(table.metricColumns(),    MAX_METRICS_PER_TABLE);
                List<String> dates      = table.dateColumns();
                List<String> dimensions = limit(table.dimensionColumns(),  MAX_DIMENSIONS_PER_TABLE);

                String primaryDate = dates.isEmpty() ? null : dates.get(0);
                EnrichedColInfo dateInfo = primaryDate != null
                        ? enriched.get(primaryDate.toLowerCase()) : null;

                for (String metric : metrics) {
                    EnrichedColInfo metricInfo = enriched.get(metric.toLowerCase());

                    String trendSql = buildTrendSql(tableRef, metric, primaryDate, provider, metricInfo, dateInfo);
                    safeExecute(trendSql, "Trend: " + metric + " over time",
                            useBQ, useSF, bqFinal, sfFinal, collected);

                    for (String dim : dimensions) {
                        String breakdownSql = buildBreakdownSql(tableRef, metric, dim, provider, metricInfo);
                        safeExecute(breakdownSql, metric + " breakdown by " + dim,
                                useBQ, useSF, bqFinal, sfFinal, collected);
                    }
                }
            }

            result.setTablesUsed(tablesUsed);

            // 5. Build KPI cards from trend data (pure Java — no extra queries)
            result.setKpiCards(buildKpiCards(collected));

            // 6. LLM synthesis
            synthesize(clientId, catalogueNode, schema, collected, result);

        } catch (IllegalStateException e) {
            result.setErrorMessage("No approved catalogue found. Please approve your catalogue first.");
            result.setConfidence(0);
            result.setKpiCards(List.of());
            result.setInsights(List.of());
            result.setInvestigations(List.of());
            result.setAnomalies(List.of());
            result.setFollowUpQuestions(List.of());
        } catch (Exception e) {
            result.setErrorMessage("Analysis failed: " + e.getMessage());
            result.setConfidence(0);
            result.setKpiCards(List.of());
            result.setInsights(List.of());
            result.setInvestigations(List.of());
            result.setAnomalies(List.of());
            result.setFollowUpQuestions(List.of());
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQL generation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a table reference appropriate for the provider:
     * - BigQuery:  bare table name  (default dataset is set in the SDK)
     * - Snowflake: SCHEMA.TABLE_NAME (uppercase)
     * - PostgreSQL: schema.tableName
     */
    private String buildTableRef(KpiDetectorService.DetectedTable table, String provider) {
        return switch (provider == null ? "" : provider) {
            case "bigquery"  -> table.tableName();
            case "snowflake" -> {
                String sc = table.tableSchema() == null || table.tableSchema().isBlank()
                        ? "PUBLIC" : table.tableSchema().toUpperCase();
                yield sc + "." + table.tableName().toUpperCase();
            }
            default -> {
                String sc = table.tableSchema() == null || table.tableSchema().isBlank()
                        ? "public" : table.tableSchema();
                yield sc + "." + table.tableName();
            }
        };
    }

    // ── Enriched column info extraction ──────────────────────────────────────

    /**
     * Builds a map of columnName (lower-cased) → EnrichedColInfo for a given table
     * from the catalogue snapshot JSON.
     */
    private Map<String, EnrichedColInfo> buildEnrichedMap(JsonNode catalogueNode, String tableName) {
        Map<String, EnrichedColInfo> map = new HashMap<>();
        for (JsonNode tableNode : catalogueNode.path("tables")) {
            if (tableName.equalsIgnoreCase(tableNode.path("tableName").asText(""))) {
                for (JsonNode col : tableNode.path("columns")) {
                    String colName = col.path("columnName").asText("");
                    if (colName.isBlank()) continue;
                    map.put(colName.toLowerCase(), new EnrichedColInfo(
                            col.path("aggregationMethod").asText("NONE"),
                            col.path("comparisonPeriod").asText("NONE"),
                            col.path("dateGranularity").asText("N/A"),
                            col.path("businessMeaning").asText(""),
                            col.path("maxValue").asText("")
                    ));
                }
                break;
            }
        }
        return map;
    }

    // ── Smart SQL generation ──────────────────────────────────────────────────

    /**
     * Builds a trend SQL query using enriched column metadata:
     * - For SUM/COUNT/AVG metrics with a known comparison period: groups by date period (WEEK/MONTH/YEAR)
     * - Uses the catalogue's stored maxValue for the date column to set a data-relative time window
     * - Falls back to a raw time series when enrichment is absent
     */
    private String buildTrendSql(
            String tableRef, String metric, String dateCol, String provider,
            EnrichedColInfo metricInfo, EnrichedColInfo dateInfo
    ) {
        boolean isBQ = "bigquery".equals(provider);
        String metricRef = isBQ ? "`" + metric + "`" : metric;

        if (dateCol == null) {
            return String.format("SELECT %s FROM %s ORDER BY %s DESC LIMIT %d",
                    metricRef, tableRef, metricRef, TREND_ROW_LIMIT);
        }

        String dateRef    = isBQ ? "`" + dateCol + "`" : dateCol;
        String aggMethod  = metricInfo != null ? metricInfo.aggregationMethod() : "NONE";
        String compPeriod = metricInfo != null ? metricInfo.comparisonPeriod()  : "NONE";
        String dataMax    = dateInfo   != null ? dateInfo.maxValue()            : "";

        // Enriched path: aggregate by period when aggregationMethod and comparisonPeriod are set
        if (List.of("SUM", "COUNT", "AVG").contains(aggMethod)
                && List.of("WoW", "MoM", "YoY").contains(compPeriod)) {

            String groupExpr = buildDateTruncExpr(dateRef, compPeriod, provider);
            String aggExpr   = buildAggExpr(metricRef, aggMethod);
            String lookback  = buildLookbackFilter(dateRef, dataMax, compPeriod, provider);

            return String.format(
                    "SELECT %s AS period, %s AS metric_value FROM %s%s GROUP BY period ORDER BY period DESC LIMIT %d",
                    groupExpr, aggExpr, tableRef, lookback, TREND_ROW_LIMIT
            );
        }

        // Default path: raw time series with optional data-relative window
        String lookback = buildLookbackFilter(dateRef, dataMax, "MoM", provider);
        return String.format("SELECT %s, %s FROM %s%s ORDER BY %s DESC LIMIT %d",
                dateRef, metricRef, tableRef, lookback, dateRef, TREND_ROW_LIMIT);
    }

    /** Builds an aggregate expression: SUM / COUNT / AVG. */
    private String buildAggExpr(String metricRef, String aggMethod) {
        return switch (aggMethod) {
            case "SUM"   -> "SUM("   + metricRef + ")";
            case "COUNT" -> "COUNT(" + metricRef + ")";
            default      -> "AVG("   + metricRef + ")";
        };
    }

    /**
     * Generates a provider-specific DATE_TRUNC expression.
     * BigQuery: DATE_TRUNC(col, WEEK)
     * Snowflake: DATE_TRUNC('WEEK', col)
     * PostgreSQL: DATE_TRUNC('week', col)
     */
    private String buildDateTruncExpr(String dateRef, String compPeriod, String provider) {
        boolean isBQ = "bigquery".equals(provider);
        boolean isSF = "snowflake".equals(provider);

        String unit = switch (compPeriod) {
            case "WoW" -> "WEEK";
            case "MoM" -> "MONTH";
            case "YoY" -> "YEAR";
            default    -> "MONTH";
        };

        if (isBQ) return "DATE_TRUNC(" + dateRef + ", " + unit + ")";
        if (isSF) return "DATE_TRUNC('" + unit + "', " + dateRef + ")";
        return "DATE_TRUNC('" + unit.toLowerCase() + "', " + dateRef + ")";
    }

    /**
     * Returns a WHERE clause fragment based on the data's actual max date from the catalogue.
     * This ensures queries target the period where real data exists, not just "today - N".
     * Returns "" if the date cannot be parsed (safe fallback — queries all data).
     */
    private String buildLookbackFilter(String dateRef, String dataMaxDate, String compPeriod, String provider) {
        LocalDate maxDate = parseDate(dataMaxDate);
        if (maxDate == null) return "";

        boolean isBQ = "bigquery".equals(provider);
        boolean isSF = "snowflake".equals(provider);

        int lookbackMonths = switch (compPeriod) {
            case "WoW" -> 6;   // 26 weeks
            case "YoY" -> 60;  // 5 years
            default    -> 24;  // 2 years (MoM default)
        };

        String startStr = maxDate.minusMonths(lookbackMonths).toString(); // YYYY-MM-DD

        if (isBQ) return " WHERE " + dateRef + " >= DATE '" + startStr + "'";
        if (isSF) return " WHERE " + dateRef + " >= '" + startStr + "'::DATE";
        return " WHERE " + dateRef + " >= '" + startStr + "'";
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank() || s.length() < 10) return null;
        try { return LocalDate.parse(s.substring(0, 10)); }
        catch (DateTimeParseException e) { return null; }
    }

    /**
     * Breakdown query using the metric's actual aggregation method.
     */
    private String buildBreakdownSql(String tableRef, String metric, String dim, String provider,
                                     EnrichedColInfo metricInfo) {
        boolean isBQ = "bigquery".equals(provider);
        String dimRef    = isBQ ? "`" + dim    + "`" : dim;
        String metricRef = isBQ ? "`" + metric + "`" : metric;

        String aggMethod = (metricInfo != null && !"NONE".equals(metricInfo.aggregationMethod()))
                ? metricInfo.aggregationMethod() : "SUM";
        String aggExpr   = buildAggExpr(metricRef, aggMethod);

        return String.format(
                "SELECT %s, %s AS total FROM %s GROUP BY %s ORDER BY total DESC LIMIT %d",
                dimRef, aggExpr, tableRef, dimRef, BREAKDOWN_ROW_LIMIT
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query execution (provider-agnostic, error-safe)
    // ─────────────────────────────────────────────────────────────────────────

    private void safeExecute(
            String sql,
            String label,
            boolean useBigQuery,
            boolean useSnowflake,
            Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg,
            List<CollectedData> out
    ) {
        try {
            List<Map<String, Object>> rows;
            if (useBigQuery && bqCfg.isPresent()) {
                var c = bqCfg.get();
                rows = bigQueryConnectorService.executeSelect(
                        c.projectId(), c.serviceAccountJson(), c.location(), c.dataset(), sql);
            } else if (useSnowflake && sfCfg.isPresent()) {
                var c = sfCfg.get();
                rows = snowflakeConnectorService.executeSelect(
                        c.account(), c.warehouse(), c.database(), c.schema(), c.username(), c.password(), sql);
            } else {
                rows = jdbcTemplate.queryForList(sql);
            }
            if (rows != null && !rows.isEmpty()) {
                out.add(new CollectedData(label, sql, rows));
            }
        } catch (Exception e) {
            System.out.println("[AgentAnalysis] Query failed for [" + label + "]: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KPI card calculation (pure Java, from trend data)
    // ─────────────────────────────────────────────────────────────────────────

    private List<AgentDashboardResult.KpiCard> buildKpiCards(List<CollectedData> collected) {
        List<AgentDashboardResult.KpiCard> cards = new ArrayList<>();

        for (CollectedData data : collected) {
            if (!data.label().startsWith("Trend:")) continue;

            List<Map<String, Object>> rows = data.rows();
            if (rows.size() < 2) continue;

            // Find the numeric column in this result set
            String metricKey = rows.get(0).keySet().stream()
                    .filter(k -> isNumericValue(rows.get(0).get(k)))
                    .findFirst()
                    .orElse(null);
            if (metricKey == null) continue;

            // Split into recent half vs previous half
            int half    = Math.max(1, rows.size() / 2);
            double curr = average(rows.subList(0, half), metricKey);
            double prev = average(rows.subList(half, rows.size()), metricKey);

            double change = prev == 0 ? 0 : ((curr - prev) / Math.abs(prev)) * 100.0;
            String direction = change > 0.5 ? "UP" : change < -0.5 ? "DOWN" : "FLAT";

            String metricName = data.label().replace("Trend: ", "").replace(" over time", "");
            String displayVal = formatValue(curr);

            cards.add(new AgentDashboardResult.KpiCard(
                    metricName, displayVal, curr, prev,
                    Math.round(change * 10.0) / 10.0, direction
            ));

            if (cards.size() >= 4) break;
        }

        return cards;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM synthesis
    // ─────────────────────────────────────────────────────────────────────────

    private void synthesize(
            String clientId,
            JsonNode catalogueNode,
            KpiDetectorService.DetectedSchema schema,
            List<CollectedData> collected,
            AgentDashboardResult result
    ) {
        String systemPrompt = buildSynthesisSystemPrompt();
        String userPrompt   = buildSynthesisUserPrompt(clientId, catalogueNode, schema, collected);

        try {
            String llmResponse = openAiClient.chat(systemPrompt, userPrompt);
            parseSynthesisResponse(llmResponse, result);
        } catch (Exception e) {
            System.out.println("[AgentAnalysis] LLM synthesis failed: " + e.getMessage());
            result.setInsights(List.of(new AgentDashboardResult.InsightCard(
                    "Analysis complete",
                    "Data was collected but insight synthesis encountered an error. Raw KPI values are available above.",
                    "MEDIUM"
            )));
            result.setInvestigations(List.of());
            result.setAnomalies(List.of());
            result.setFollowUpQuestions(List.of("What are the main trends?", "Show me the top metrics", "Compare recent vs historical data"));
            result.setReasoning("Data collected from " + collected.size() + " queries. LLM synthesis failed.");
            result.setConfidence(30);
        }
    }

    private String buildSynthesisSystemPrompt() {
        return """
                You are a senior business intelligence analyst at a data consultancy.
                You receive raw query results from a company's database and produce a structured dashboard update.
                
                Your output MUST be valid JSON in exactly this format:
                {
                  "insights": [
                    { "title": "...", "description": "...", "impactLevel": "HIGH" }
                  ],
                  "investigations": [
                    { "title": "...", "description": "..." }
                  ],
                  "anomalies": [
                    { "metric": "...", "description": "...", "changePercent": 18.0, "direction": "UP" }
                  ],
                  "followUpQuestions": ["...", "...", "..."],
                  "reasoning": "...",
                  "confidence": 85
                }
                
                RULES:
                - insights: generate 3 cards. Include specific numbers and percentages in titles. impactLevel: HIGH (>10% shift or critical), MEDIUM (5-10%), LOW (<5%), POSITIVE (improvement/growth).
                - investigations: 2 items — things worth investigating further based on what you see.
                - anomalies: up to 3 unusual patterns (spikes, drops, outliers). If none exist, return [].
                - followUpQuestions: 4 natural follow-up questions a business user would ask next.
                - reasoning: 1-2 sentences describing what you analysed and how.
                - confidence: 0-100. High (80+) if data is rich and recent; lower if sparse or old.
                - Be specific: mention actual column names, table names, and real numbers from the data.
                - Do NOT invent data. Only describe what the provided data actually shows.
                """;
    }

    private String buildSynthesisUserPrompt(
            String clientId,
            JsonNode catalogueNode,
            KpiDetectorService.DetectedSchema schema,
            List<CollectedData> collected
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLIENT: ").append(clientId).append("\n\n");

        // Schema summary — include business meanings for metric columns
        sb.append("SCHEMA OVERVIEW\n===============\n");
        for (KpiDetectorService.DetectedTable t : schema.analysableTables()) {
            Map<String, EnrichedColInfo> enriched = buildEnrichedMap(catalogueNode, t.tableName());
            sb.append("Table: ").append(t.tableName()).append("\n");
            sb.append("  Metric columns:\n");
            for (String m : t.metricColumns()) {
                EnrichedColInfo info = enriched.get(m.toLowerCase());
                String meaning = (info != null && !info.businessMeaning().isBlank())
                        ? info.businessMeaning() : "numeric metric";
                String agg = (info != null) ? info.aggregationMethod() : "?";
                String period = (info != null) ? info.comparisonPeriod() : "?";
                sb.append("    - ").append(m)
                  .append(" [agg=").append(agg)
                  .append(", compare=").append(period)
                  .append("]: ").append(meaning).append("\n");
            }
            sb.append("  Date columns: ").append(t.dateColumns()).append("\n");
            sb.append("  Dimension columns: ").append(t.dimensionColumns()).append("\n");
        }
        sb.append("\n");

        // Collected data — one section per query
        sb.append("COLLECTED DATA\n==============\n");
        for (CollectedData data : collected) {
            sb.append("## ").append(data.label()).append("\n");
            List<Map<String, Object>> rows = data.rows();
            int show = Math.min(rows.size(), 25);

            // Header
            if (!rows.isEmpty()) {
                sb.append(String.join(" | ", rows.get(0).keySet())).append("\n");
                sb.append("-".repeat(60)).append("\n");
            }
            for (int i = 0; i < show; i++) {
                Map<String, Object> row = rows.get(i);
                sb.append(String.join(" | ", row.values().stream()
                        .map(v -> v == null ? "null" : v.toString())
                        .toList())).append("\n");
            }
            if (rows.size() > show) {
                sb.append("... (").append(rows.size() - show).append(" more rows)\n");
            }
            sb.append("\n");
        }

        sb.append("Based on the above data, generate the dashboard update JSON.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void parseSynthesisResponse(String llmResponse, AgentDashboardResult result) {
        try {
            JsonNode root = objectMapper.readTree(llmResponse);

            // insights
            List<AgentDashboardResult.InsightCard> insights = new ArrayList<>();
            for (JsonNode n : root.path("insights")) {
                insights.add(new AgentDashboardResult.InsightCard(
                        n.path("title").asText(""),
                        n.path("description").asText(""),
                        n.path("impactLevel").asText("MEDIUM")
                ));
            }
            result.setInsights(insights);

            // investigations
            List<AgentDashboardResult.Investigation> investigations = new ArrayList<>();
            for (JsonNode n : root.path("investigations")) {
                investigations.add(new AgentDashboardResult.Investigation(
                        n.path("title").asText(""),
                        n.path("description").asText(""),
                        "SUGGESTED"
                ));
            }
            result.setInvestigations(investigations);

            // anomalies
            List<AgentDashboardResult.Anomaly> anomalies = new ArrayList<>();
            for (JsonNode n : root.path("anomalies")) {
                anomalies.add(new AgentDashboardResult.Anomaly(
                        n.path("metric").asText(""),
                        n.path("description").asText(""),
                        n.path("changePercent").asDouble(0),
                        n.path("direction").asText("UP")
                ));
            }
            result.setAnomalies(anomalies);

            // follow-up questions
            List<String> questions = new ArrayList<>();
            for (JsonNode n : root.path("followUpQuestions")) {
                questions.add(n.asText());
            }
            result.setFollowUpQuestions(questions);

            result.setReasoning(root.path("reasoning").asText("Analysis complete."));
            result.setConfidence(root.path("confidence").asInt(75));

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM synthesis response: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private double average(List<Map<String, Object>> rows, String key) {
        if (rows.isEmpty()) return 0;
        double sum = 0;
        int count = 0;
        for (Map<String, Object> row : rows) {
            Object val = row.get(key);
            if (val != null) {
                try { sum += Double.parseDouble(val.toString()); count++; }
                catch (NumberFormatException ignored) {}
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private boolean isNumericValue(Object val) {
        if (val == null) return false;
        try { Double.parseDouble(val.toString()); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private String formatValue(double v) {
        if (v >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (v >= 1_000)     return String.format("%.1fK", v / 1_000);
        return String.format("%.2f", v);
    }

    private <T> List<T> limit(List<T> list, int max) {
        if (list.size() <= max) return list;
        return list.subList(0, max);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal value objects
    // ─────────────────────────────────────────────────────────────────────────

    private record CollectedData(String label, String sql, List<Map<String, Object>> rows) {}

    /** Per-column enrichment data extracted from the catalogue snapshot. */
    private record EnrichedColInfo(
            String aggregationMethod,
            String comparisonPeriod,
            String dateGranularity,
            String businessMeaning,
            String maxValue
    ) {}
}
