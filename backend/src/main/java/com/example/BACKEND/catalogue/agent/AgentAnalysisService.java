package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
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

    // Universal sampling limits — independent of data type
    private static final int RAW_SAMPLE_LIMIT     = 100;  // rows fetched per table
    private static final int RAW_SAMPLE_SHOW      = 40;   // rows shown to LLM per table
    private static final int DIST_TOP_N           = 15;   // top N values per categorical column
    private static final int MAX_STRING_COLS      = 5;    // max categorical distributions per table
    private static final int MAX_NUMERIC_COLS     = 3;    // max numeric trends per table
    private static final int TIME_DIST_MONTHS     = 24;   // months of time distribution
    private static final int TREND_ROW_LIMIT      = 60;
    private static final int BREAKDOWN_ROW_LIMIT  = 10;

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

            if ("bigquery".equalsIgnoreCase(provider)) {
                bqCfg = cloudConnectionService.getBigQueryConfig(clientId);
            } else if ("snowflake".equalsIgnoreCase(provider)) {
                sfCfg = cloudConnectionService.getSnowflakeConfig(clientId);
            }

            boolean useBigQuery  = bqCfg.isPresent() && notBlank(bqCfg.get().projectId());
            boolean useSnowflake = sfCfg.isPresent() && notBlank(sfCfg.get().account());

            result.setDataSource(useBigQuery ? "BigQuery" : useSnowflake ? "Snowflake" : "PostgreSQL");

            final Optional<TenantCloudConnectionService.BigQueryConfig>  bqFinal = bqCfg;
            final Optional<TenantCloudConnectionService.SnowflakeConfig> sfFinal = sfCfg;
            final boolean useBQ = useBigQuery;
            final boolean useSF = useSnowflake;

            List<CollectedData> collected = new ArrayList<>();
            List<String> tablesUsed = new ArrayList<>();
            List<KpiDetectorService.ColumnHints> allHints = new ArrayList<>();

            // 3. Universal sampling loop — every table, every data type
            for (JsonNode tableNode : catalogueNode.path("tables")) {
                KpiDetectorService.ColumnHints hints = kpiDetector.classifyColumns(tableNode);
                if (hints.tableName().isBlank()) continue;

                tablesUsed.add(hints.tableName());
                allHints.add(hints);
                String tableRef = buildTableRef(hints.tableName(), hints.tableSchema(), provider);
                Map<String, EnrichedColInfo> enriched = buildEnrichedMap(catalogueNode, hints.tableName());

                // ── Query 1: Raw data sample ─────────────────────────────
                // Gives the LLM real rows to reason over — works for ANY data type
                String sampleSql = buildRawSampleSql(tableRef, hints.dateCol(), provider);
                safeExecute(sampleSql, "Sample rows from " + hints.tableName(),
                        useBQ, useSF, bqFinal, sfFinal, collected);

                // ── Query 2: Categorical distributions ───────────────────
                // For each string/categorical column: what values dominate and how many?
                for (String col : limit(hints.stringCols(), MAX_STRING_COLS)) {
                    String distSql = buildCatDistributionSql(tableRef, col, provider);
                    safeExecute(distSql, "Distribution of " + col + " in " + hints.tableName(),
                            useBQ, useSF, bqFinal, sfFinal, collected);
                }

                // ── Query 3: Time distribution ───────────────────────────
                // How many records per month? Reveals trends for ANY type of data
                if (hints.dateCol() != null) {
                    String timeSql = buildTimeDistributionSql(tableRef, hints.dateCol(), provider, enriched);
                    safeExecute(timeSql, "Activity over time in " + hints.tableName(),
                            useBQ, useSF, bqFinal, sfFinal, collected);
                }

                // ── Query 4: Numeric trends (optional) ───────────────────
                // Only when numeric columns exist — treated as bonus context, not required
                for (String numCol : limit(hints.numericCols(), MAX_NUMERIC_COLS)) {
                    EnrichedColInfo metricInfo = enriched.get(numCol.toLowerCase());
                    EnrichedColInfo dateInfo   = hints.dateCol() != null
                            ? enriched.get(hints.dateCol().toLowerCase()) : null;
                    String trendSql = buildTrendSql(tableRef, numCol, hints.dateCol(), provider, metricInfo, dateInfo);
                    safeExecute(trendSql, "Trend: " + numCol + " over time",
                            useBQ, useSF, bqFinal, sfFinal, collected);
                }
            }

            result.setTablesUsed(tablesUsed);

            // 4. Guard: no data collected → don't hallucinate
            if (collected.isEmpty()) {
                result.setErrorMessage(
                        "No data returned from " + String.join(", ", tablesUsed) + ". "
                        + "Check that the data source is accessible and contains rows.");
                result.setConfidence(0);
                result.setKpiCards(List.of());
                result.setInsights(List.of());
                result.setInvestigations(List.of());
                result.setAnomalies(List.of());
                result.setFollowUpQuestions(List.of());
                return result;
            }

            // 5. KPI cards — only from numeric trend data; absent for pure-text tables
            result.setKpiCards(buildKpiCards(collected));

            // 6. LLM synthesis — universal analyst prompt
            synthesize(clientId, catalogueNode, allHints, collected, result);

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
     * - BigQuery:  bare table name  (dataset is set in the SDK)
     * - Snowflake: SCHEMA.TABLE_NAME (uppercase)
     * - PostgreSQL: schema.tableName
     */
    private String buildTableRef(String tableName, String tableSchema, String provider) {
        return switch (provider == null ? "" : provider) {
            case "bigquery"  -> tableName;
            case "snowflake" -> {
                String sc = tableSchema == null || tableSchema.isBlank()
                        ? "PUBLIC" : tableSchema.toUpperCase();
                yield sc + "." + tableName.toUpperCase();
            }
            default -> {
                String sc = tableSchema == null || tableSchema.isBlank()
                        ? "public" : tableSchema;
                yield sc + "." + tableName;
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

    /**
     * Raw sample: returns real rows the LLM can reason over regardless of data type.
     * If a date column is known, order by it descending so the most recent data comes first.
     */
    private String buildRawSampleSql(String tableRef, String dateCol, String provider) {
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);
        if (dateCol != null) {
            String dateRef = isBQ ? "`" + dateCol + "`" : dateCol;
            return String.format("SELECT * FROM %s ORDER BY %s DESC LIMIT %d",
                    tableRef, dateRef, RAW_SAMPLE_LIMIT);
        }
        return String.format("SELECT * FROM %s LIMIT %d", tableRef, RAW_SAMPLE_LIMIT);
    }

    /**
     * Categorical distribution: top-N values and their counts for a string column.
     * Reveals dominant categories, imbalances, and surprises (e.g. "Drama: 4,000, Comedy: 3,200").
     */
    private String buildCatDistributionSql(String tableRef, String col, String provider) {
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);
        String colRef = isBQ ? "`" + col + "`" : col;
        return String.format(
                "SELECT %s, COUNT(*) AS count FROM %s WHERE %s IS NOT NULL GROUP BY %s ORDER BY count DESC LIMIT %d",
                colRef, tableRef, colRef, colRef, DIST_TOP_N
        );
    }

    /**
     * Time distribution: number of records per calendar month (or year for sparse data).
     * Works on ANY table with a date column — numeric metrics are not required.
     */
    private String buildTimeDistributionSql(String tableRef, String dateCol, String provider,
                                            Map<String, EnrichedColInfo> enriched) {
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);
        boolean isSF = "snowflake".equalsIgnoreCase(provider);
        String dateRef = isBQ ? "`" + dateCol + "`" : dateCol;
        String truncExpr = AgentSqlHelper.dateTruncMonth(dateRef, provider);

        return String.format(
                "SELECT %s AS month, COUNT(*) AS records FROM %s WHERE %s IS NOT NULL GROUP BY 1 ORDER BY 1 DESC LIMIT %d",
                truncExpr, tableRef, dateRef, TIME_DIST_MONTHS
        );
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
        String unit = switch (compPeriod) {
            case "WoW" -> "WEEK";
            case "MoM" -> "MONTH";
            case "YoY" -> "YEAR";
            default    -> "MONTH";
        };
        return AgentSqlHelper.dateTrunc(dateRef, unit, provider);
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
        String dateExpr = AgentSqlHelper.asDateExpr(dateRef, provider);

        if (isBQ) return " WHERE " + dateExpr + " >= DATE '" + startStr + "'";
        if (isSF) return " WHERE " + dateExpr + " >= '" + startStr + "'::DATE";
        return " WHERE " + dateExpr + " >= '" + startStr + "'";
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
            List<KpiDetectorService.ColumnHints> allHints,
            List<CollectedData> collected,
            AgentDashboardResult result
    ) {
        String systemPrompt = buildSynthesisSystemPrompt();
        String userPrompt   = buildSynthesisUserPrompt(clientId, catalogueNode, allHints, collected);

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
                You are a senior data analyst. You receive real data samples from a company's database —
                rows, categorical distributions, and time trends. Your job is to produce a structured feed
                of insight cards that feel like alerts from an intelligent analyst, not a generic dashboard.

                The data can be ANYTHING — retail orders, Netflix content, sales pipeline, stock prices,
                HR records, delivery logs. Work with whatever is there. Never say the data type limits you.

                Look for:
                - Dominant patterns: what's most common and why it matters to the business
                - Changes over time: growth, decline, seasonality, sudden spikes or drops
                - Distribution surprises: unexpected concentrations, missing values, imbalances
                - Business implications: what does this pattern mean for the company?
                - Concrete, specific strategies grounded in the actual data shown

                Your output MUST be valid JSON in exactly this format:
                {
                  "insights": [
                    {
                      "title": "Drama outpaces Comedy 3-to-1 in catalogue — but additions have stalled",
                      "description": "Drama accounts for 35% of catalogue but received only 12% of new additions in 2021...",
                      "impactLevel": "HIGH",
                      "badge": "ALERT",
                      "agentName": "Content Mix agent",
                      "metricHighlights": [
                        {"label": "Drama share", "value": "35%"},
                        {"label": "New drama titles", "value": "12%"},
                        {"label": "Comedy gap", "value": "+8 pp"}
                      ],
                      "reasons": [
                        "Drama titles dominate historical catalogue but 2021 additions skew heavily toward International content",
                        "The distribution shows Comedy at 28% of existing titles yet 40% of new 2021 releases — an inversion"
                      ],
                      "strategies": [
                        "Re-balance Drama acquisitions in the next content cycle to close the 23-percentage-point addition gap",
                        "Investigate whether Drama audience retention data justifies current catalogue proportion before increasing spend"
                      ]
                    }
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
                - insights: generate 3-5 cards. Titles must be punchy headlines with specific numbers or facts from the data ("Drama accounts for 35% of catalogue", "Returns spiked 67% in Q4"). Each card should read like an alert from an intelligent agent.
                - badge: "ALERT" (critical issues, drops, risks), "RISK" (warning signals), "OPPORTUNITY" (growth gaps, wins), "INFO" (neutral but notable patterns)
                - agentName: name the agent after the subject being analysed (e.g. "Content Mix agent", "Sales Pipeline agent", "Delivery agent")
                - metricHighlights: exactly 3 mini chips — the most important numbers, percentages, or facts extracted directly from the data
                - reasons: exactly 2-3 specific, data-backed reasons explaining WHY this pattern exists. Reference actual values from the data. Do NOT be generic — be precise.
                - strategies: exactly 2-3 actionable, concrete strategies the business should take. Tailor to the domain and actual numbers shown.
                - impactLevel: "HIGH" (major pattern or critical risk), "MEDIUM" (notable but not urgent), "LOW" (minor observation), "POSITIVE" (clear growth or win)
                - investigations: 2 areas that need deeper investigation, based on what the data hints at but doesn't fully explain
                - anomalies: up to 3 unusual patterns (unexpected spikes, gaps, outliers). Return [] if none found.
                - followUpQuestions: 4 natural business questions a user would ask next, grounded in the data shown
                - reasoning: 1-2 sentences describing what data was analysed and what approach was taken
                - confidence: 0-100 reflecting how clearly the data supports the insights
                - NEVER invent data not present in the query results
                - NEVER say "I cannot analyse this" — always find something meaningful in what is provided
                """;
    }

    private String buildSynthesisUserPrompt(
            String clientId,
            JsonNode catalogueNode,
            List<KpiDetectorService.ColumnHints> allHints,
            List<CollectedData> collected
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLIENT: ").append(clientId).append("\n\n");

        // Schema overview — describe what columns exist in each table
        sb.append("SCHEMA OVERVIEW\n===============\n");
        for (KpiDetectorService.ColumnHints hints : allHints) {
            Map<String, EnrichedColInfo> enriched = buildEnrichedMap(catalogueNode, hints.tableName());
            sb.append("Table: ").append(hints.tableName()).append("\n");
            if (hints.dateCol() != null) {
                sb.append("  Date column: ").append(hints.dateCol()).append("\n");
            }
            if (!hints.numericCols().isEmpty()) {
                sb.append("  Numeric columns: ");
                for (String col : hints.numericCols()) {
                    EnrichedColInfo info = enriched.get(col.toLowerCase());
                    String meaning = (info != null && !info.businessMeaning().isBlank())
                            ? info.businessMeaning() : "numeric";
                    sb.append(col).append(" (").append(meaning).append("), ");
                }
                sb.append("\n");
            }
            if (!hints.stringCols().isEmpty()) {
                sb.append("  Categorical columns: ").append(String.join(", ", hints.stringCols())).append("\n");
            }
        }
        sb.append("\n");

        // Collected data — one section per query
        // Raw sample rows are capped at RAW_SAMPLE_SHOW to control token usage
        sb.append("COLLECTED DATA\n==============\n");
        for (CollectedData data : collected) {
            sb.append("## ").append(data.label()).append("\n");
            List<Map<String, Object>> rows = data.rows();

            // Raw samples: cap at RAW_SAMPLE_SHOW; distributions/trends: show all (they're compact)
            int show = data.label().startsWith("Sample rows")
                    ? Math.min(rows.size(), RAW_SAMPLE_SHOW)
                    : Math.min(rows.size(), 30);

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

        sb.append("Based on ALL the above data, generate the dashboard insight JSON. ");
        sb.append("Analyse patterns, trends, distributions, and anomalies across every table shown. ");
        sb.append("Be specific — use actual values from the data, not generic statements.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void parseSynthesisResponse(String llmResponse, AgentDashboardResult result) {
        try {
            JsonNode root = objectMapper.readTree(llmResponse);

            // insights
            List<AgentDashboardResult.InsightCard> insights = new ArrayList<>();
            for (JsonNode n : root.path("insights")) {
                AgentDashboardResult.InsightCard card = new AgentDashboardResult.InsightCard(
                        n.path("title").asText(""),
                        n.path("description").asText(""),
                        n.path("impactLevel").asText("MEDIUM")
                );
                card.setBadge(n.path("badge").asText("INFO"));
                card.setAgentName(n.path("agentName").asText("Analysis agent"));

                List<AgentDashboardResult.MetricHighlight> highlights = new ArrayList<>();
                for (JsonNode h : n.path("metricHighlights")) {
                    highlights.add(new AgentDashboardResult.MetricHighlight(
                            h.path("label").asText(""),
                            h.path("value").asText("")
                    ));
                }
                card.setMetricHighlights(highlights);

                List<String> actions = new ArrayList<>();
                for (JsonNode a : n.path("suggestedActions")) actions.add(a.asText());
                card.setSuggestedActions(actions);

                List<String> reasons = new ArrayList<>();
                for (JsonNode r : n.path("reasons")) reasons.add(r.asText());
                card.setReasons(reasons);

                List<String> strategies = new ArrayList<>();
                for (JsonNode s : n.path("strategies")) strategies.add(s.asText());
                card.setStrategies(strategies);

                insights.add(card);
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
