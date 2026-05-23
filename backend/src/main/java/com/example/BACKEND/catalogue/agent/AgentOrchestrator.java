package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.agents.AnomalyAgent;
import com.example.BACKEND.catalogue.agent.agents.CrossTableAgent;
import com.example.BACKEND.catalogue.agent.agents.DistributionAgent;
import com.example.BACKEND.catalogue.agent.agents.KpiPerformanceAgent;
import com.example.BACKEND.catalogue.agent.agents.ForecastingAgent;
import com.example.BACKEND.catalogue.agent.agents.RootCauseAnalysisAgent;
import com.example.BACKEND.catalogue.agent.agents.TrendAgent;
import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coordinates all specialised agents for a tenant's "Refresh Insights" request.
 *
 * Architecture:
 *   1. Load the approved catalogue snapshot
 *   2. Detect provider (BigQuery / Snowflake / PostgreSQL) + credentials
 *   3. For each table in the catalogue, create a TableContext and run:
 *        - TrendAgent       — how metrics change over time + breakdowns by dimension
 *        - DistributionAgent — categorical distributions + monthly volume
 *        - KpiPerformanceAgent — current vs prior period per metric (also builds KPI cards)
 *        - Raw sample query — universal fallback: real rows for LLM reasoning on any data type
 *   4. Merge all collected data, guard against empty results
 *   5. Single LLM synthesis call → AgentDashboardResult
 *
 * The specialised agents own the SQL generation and query execution for their domain.
 * The orchestrator owns the synthesis prompt, LLM call, and result assembly.
 *
 * This replaces AgentAnalysisService as the controller entry-point while keeping
 * the legacy service as a fallback during the transition.
 */
@Service
public class AgentOrchestrator {

    private static final int RAW_SAMPLE_LIMIT  = 100;
    private static final int RAW_SAMPLE_SHOW   = 40;
    private static final int CARD_TTL_DAYS     = 7;

    private final CatalogueApprovalService      approvalService;
    private final KpiDetectorService            kpiDetector;
    private final TenantCloudConnectionService  cloudConnectionService;
    private final BigQueryConnectorService      bigQueryConnectorService;
    private final SnowflakeConnectorService     snowflakeConnectorService;
    private final JdbcTemplate                  jdbcTemplate;
    private final OpenAiClient                  openAiClient;
    private final ObjectMapper                  objectMapper;
    private final InsightCardRepository         insightCardRepository;
    private final DecisionMemoryService         decisionMemoryService;
    private final SignalReadinessChecker        readinessChecker;

    private final TrendAgent            trendAgent;
    private final DistributionAgent     distributionAgent;
    private final KpiPerformanceAgent   kpiPerformanceAgent;
    private final AnomalyAgent              anomalyAgent;
    private final CrossTableAgent           crossTableAgent;
    private final RootCauseAnalysisAgent    rootCauseAgent;
    private final ForecastingAgent          forecastingAgent;
    private final InsightNarrativeEnricher  narrativeEnricher;

    public AgentOrchestrator(
            CatalogueApprovalService    approvalService,
            KpiDetectorService          kpiDetector,
            TenantCloudConnectionService cloudConnectionService,
            BigQueryConnectorService    bigQueryConnectorService,
            SnowflakeConnectorService   snowflakeConnectorService,
            JdbcTemplate                jdbcTemplate,
            OpenAiClient                openAiClient,
            ObjectMapper                objectMapper,
            InsightCardRepository       insightCardRepository,
            DecisionMemoryService       decisionMemoryService,
            SignalReadinessChecker      readinessChecker,
            TrendAgent                  trendAgent,
            DistributionAgent           distributionAgent,
            KpiPerformanceAgent         kpiPerformanceAgent,
            AnomalyAgent                anomalyAgent,
            CrossTableAgent             crossTableAgent,
            RootCauseAnalysisAgent      rootCauseAgent,
            ForecastingAgent            forecastingAgent,
            InsightNarrativeEnricher    narrativeEnricher
    ) {
        this.approvalService           = approvalService;
        this.kpiDetector               = kpiDetector;
        this.cloudConnectionService    = cloudConnectionService;
        this.bigQueryConnectorService  = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.jdbcTemplate              = jdbcTemplate;
        this.openAiClient              = openAiClient;
        this.objectMapper              = objectMapper;
        this.insightCardRepository     = insightCardRepository;
        this.decisionMemoryService     = decisionMemoryService;
        this.readinessChecker          = readinessChecker;
        this.trendAgent                = trendAgent;
        this.distributionAgent         = distributionAgent;
        this.kpiPerformanceAgent       = kpiPerformanceAgent;
        this.anomalyAgent              = anomalyAgent;
        this.crossTableAgent           = crossTableAgent;
        this.rootCauseAgent            = rootCauseAgent;
        this.forecastingAgent          = forecastingAgent;
        this.narrativeEnricher         = narrativeEnricher;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    public AgentDashboardResult analyse(String clientId) {
        AgentDashboardResult result = new AgentDashboardResult();
        result.setLastUpdated(Instant.now().toString());

        try {
            // 1. Readiness check — skip if data source is unreachable or catalogue missing
            SignalReadinessChecker.ReadinessReport readiness = readinessChecker.check(clientId);
            if (!readiness.ready()) {
                emptyResult(result, "Not ready: " + readiness.summary());
                return result;
            }

            // 2. Load catalogue
            String   snapshotJson  = approvalService.getApprovedSnapshot(clientId);
            JsonNode catalogueNode = objectMapper.readTree(snapshotJson);

            // 3. Detect provider + credentials
            String provider = cloudConnectionService.getProvider(clientId);
            Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg = Optional.empty();
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg = Optional.empty();

            if ("bigquery".equalsIgnoreCase(provider)) {
                bqCfg = cloudConnectionService.getBigQueryConfig(clientId);
            } else if ("snowflake".equalsIgnoreCase(provider)) {
                sfCfg = cloudConnectionService.getSnowflakeConfig(clientId);
            }

            boolean useBQ = bqCfg.isPresent() && notBlank(bqCfg.get().projectId());
            boolean useSF = sfCfg.isPresent() && notBlank(sfCfg.get().account());
            result.setDataSource(useBQ ? "BigQuery" : useSF ? "Snowflake" : "PostgreSQL");

            List<CollectedData>                          collected      = new ArrayList<>();
            List<AgentDashboardResult.KpiCard>           kpiCards       = new ArrayList<>();
            List<AgentDashboardResult.Anomaly>           anomalies      = new ArrayList<>();
            List<AgentDashboardResult.InsightCard>       rootCauseCards = new ArrayList<>();
            List<AgentDashboardResult.InsightCard>       forecastCards  = new ArrayList<>();
            List<String>                                 tables         = new ArrayList<>();
            List<KpiDetectorService.ColumnHints>         allHints       = new ArrayList<>();

            // 4. Per-table agent loop
            for (JsonNode tableNode : catalogueNode.path("tables")) {
                KpiDetectorService.ColumnHints hints = kpiDetector.classifyColumns(tableNode);
                if (hints.tableName().isBlank()) continue;

                tables.add(hints.tableName());
                allHints.add(hints);

                String tableRef = buildTableRef(hints.tableName(), hints.tableSchema(), provider);
                Map<String, EnrichedColInfo> enriched = buildEnrichedMap(catalogueNode, hints.tableName());

                TableContext ctx = new TableContext(
                        clientId, hints, enriched, tableRef, provider,
                        useBQ, useSF, bqCfg, sfCfg, jdbcTemplate
                );

                // 3a. Raw sample — universal fallback, works on any data type
                safeExecute(buildRawSampleSql(tableRef, hints.dateCol(), provider),
                        "Sample rows from " + hints.tableName(), ctx, collected);

                // 3b. TrendAgent — time-series + dimension breakdowns for metric columns
                collected.addAll(trendAgent.collectData(ctx));

                // 3c. DistributionAgent — categorical distributions + monthly volume
                collected.addAll(distributionAgent.collectData(ctx));

                // 3d. KpiPerformanceAgent — current vs prior period + KPI cards
                KpiPerformanceAgent.KpiResult kpiResult = kpiPerformanceAgent.collectData(ctx);
                collected.addAll(kpiResult.collected());
                kpiCards.addAll(kpiResult.cards());

                // 3e. AnomalyAgent — Z-score statistical outlier detection
                List<AgentDashboardResult.Anomaly> tableAnomalies = anomalyAgent.detectAnomalies(ctx);
                anomalies.addAll(tableAnomalies);

                // 3f. RootCauseAnalysisAgent — ReAct multi-step drill-down on high anomalies
                if (!tableAnomalies.isEmpty()) {
                    List<AgentDashboardResult.InsightCard> rcCards =
                            rootCauseAgent.investigate(tableAnomalies, ctx, catalogueNode);
                    rootCauseCards.addAll(rcCards);
                }
            }

            // 5. CrossTableAgent — fact × dimension join analysis
            collected.addAll(crossTableAgent.collectData(
                    clientId, catalogueNode, provider,
                    useBQ, useSF, bqCfg, sfCfg, jdbcTemplate
            ));

            // 6. ForecastingAgent — extrapolate next 3 periods from collected time-series
            for (String tableName : tables) {
                List<CollectedData> tableData = collected.stream()
                        .filter(d -> d.label().contains(tableName)
                                  || d.label().startsWith("Trend:")
                                  || d.label().startsWith("KPI:"))
                        .toList();
                if (!tableData.isEmpty()) {
                    forecastCards.addAll(forecastingAgent.forecast(tableData, tableName));
                }
            }

            result.setTablesUsed(tables);

            // 4. Guard: no data → don't call LLM, no hallucinations
            if (collected.isEmpty()) {
                result.setErrorMessage(
                        "No data returned from " + String.join(", ", tables) + ". "
                        + "Check that the data source is accessible and contains rows.");
                result.setConfidence(0);
                result.setKpiCards(List.of());
                result.setInsights(List.of());
                result.setInvestigations(List.of());
                result.setAnomalies(List.of());
                result.setFollowUpQuestions(List.of());
                return result;
            }

            // 6. Attach KPI cards (prefer KpiAgent's richer versions over legacy)
            result.setKpiCards(kpiCards.isEmpty() ? legacyKpiCards(collected) : kpiCards);

            // 7. Attach anomalies detected in Java (LLM synthesis may add more)
            result.setAnomalies(anomalies);

            // 8. Single LLM synthesis over all collected data
            synthesize(clientId, catalogueNode, allHints, collected, result);

            // 9. Merge Java-detected anomalies with LLM-detected ones (deduplicate by metric)
            if (!anomalies.isEmpty()) {
                List<AgentDashboardResult.Anomaly> merged = new ArrayList<>(anomalies);
                if (result.getAnomalies() != null) {
                    for (AgentDashboardResult.Anomaly llmAnomaly : result.getAnomalies()) {
                        boolean alreadyDetected = anomalies.stream()
                                .anyMatch(a -> a.getMetric().equalsIgnoreCase(llmAnomaly.getMetric()));
                        if (!alreadyDetected) merged.add(llmAnomaly);
                    }
                }
                result.setAnomalies(merged);
            }

            // 10. Merge: root cause first (urgent), then LLM insights, then forecasts (future-looking)
            {
                List<AgentDashboardResult.InsightCard> merged = new ArrayList<>();
                merged.addAll(rootCauseCards);
                if (result.getInsights() != null) merged.addAll(result.getInsights());
                merged.addAll(forecastCards);
                result.setInsights(merged);
            }

            // 10b. OpenAI rewrites reasons + strategies for EVERY card from full collected data
            if (result.getInsights() != null && !result.getInsights().isEmpty()) {
                narrativeEnricher.enrichAll(result.getInsights(), collected);
            }

            // 11. Apply decision memory — adjust confidence based on past user behaviour
            if (result.getInsights() != null) {
                for (AgentDashboardResult.InsightCard card : result.getInsights()) {
                    int adjusted = decisionMemoryService.adjustConfidence(
                            clientId, card.getBadge(), card.getAgentName(), result.getConfidence());
                    result.setConfidence(adjusted);
                }
            }

            // 12. Persist insight cards — expire stale ones, save the new batch
            // Also maps the saved DB UUIDs back onto the result cards so the
            // frontend can call the /evidence endpoint immediately after Refresh.
            if (result.getInsights() != null && !result.getInsights().isEmpty()) {
                String evidence = buildEvidenceDigest(collected);
                persistInsightsAndTag(clientId, result.getInsights(), result.getConfidence(), evidence);
            }

        } catch (IllegalStateException e) {
            emptyResult(result, "No approved catalogue found. Please approve your catalogue first.");
        } catch (Exception e) {
            emptyResult(result, "Analysis failed: " + e.getMessage());
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM synthesis  (prompts intentionally match AgentAnalysisService)
    // ─────────────────────────────────────────────────────────────────────────

    private void synthesize(
            String clientId,
            JsonNode catalogueNode,
            List<KpiDetectorService.ColumnHints> allHints,
            List<CollectedData> collected,
            AgentDashboardResult result
    ) {
        String system = buildSystemPrompt();
        String user   = buildUserPrompt(clientId, catalogueNode, allHints, collected);
        try {
            String llmResponse = openAiClient.chat(system, user);
            parseSynthesisResponse(llmResponse, result);
        } catch (Exception e) {
            System.out.println("[AgentOrchestrator] LLM synthesis failed: " + e.getMessage());
            result.setInsights(List.of(new AgentDashboardResult.InsightCard(
                    "Analysis complete",
                    "Data was collected but insight synthesis encountered an error. Raw KPI values are available above.",
                    "MEDIUM")));
            result.setInvestigations(List.of());
            result.setAnomalies(List.of());
            result.setFollowUpQuestions(List.of(
                    "What are the main trends?", "Show me the top metrics",
                    "Compare recent vs historical data"));
            result.setReasoning("Data collected from " + collected.size() + " queries. LLM synthesis failed.");
            result.setConfidence(30);
        }
    }

    private String buildSystemPrompt() {
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

                ══════════════════════════════════════════════════════════
                CRITICAL — DATA FIDELITY (read carefully, violations destroy accuracy):
                ══════════════════════════════════════════════════════════

                1. USE ONLY EXACT COLUMN VALUES — NEVER INVENT CATEGORIES.
                   If a category column contains "Electronics", "Beauty", "Fashion" — those are the ONLY
                   labels you may use. NEVER merge or rename them into invented groups like "Home Products",
                   "Consumer Goods", "Tech Items", or any label not present verbatim in the data.
                   If you want to highlight a pattern in "Electronics", write "Electronics" — not "Tech".

                2. EVERY NUMBER IN metricHighlights MUST BE DIRECTLY TRACEABLE.
                   Before writing a metric like "25 orders" or "20% return rate", find the exact row
                   or aggregation in the data provided that supports it. If you cannot point to a specific
                   data row, DO NOT claim the number. Write "see data" if a precise figure is unavailable.

                3. TITLES MUST USE ACTUAL DATA LABELS.
                   Good: "Electronics Returns Spike to 18% — Highest Across All Categories"
                   Bad:  "Home Products Dominate Sales — But Returns Are High!" (if 'Home Products' ≠ a real value)

                4. QUOTED REASONING.
                   In the "reasons" field, prefix each reason with the actual data value that supports it.
                   Example: "Electronics (38% of orders): return rate is 18%, double the average of 9%"
                   This keeps every reason auditable against the raw data shown.

                5. DO NOT HALLUCINATE TRENDS.
                   Only describe a "trend" (increase/decrease over time) if the time-series data explicitly
                   shows multiple periods with different values. If only a single period is in the data,
                   describe the current state only — not a trend.

                ══════════════════════════════════════════════════════════

                Your output MUST be valid JSON in exactly this format:
                {
                  "insights": [
                    {
                      "title": "Electronics Returns Hit 18% — Double the Store Average",
                      "description": "Electronics accounts for 38% of orders but carries the highest return rate at 18%, compared to the store average of 9%.",
                      "impactLevel": "HIGH",
                      "badge": "ALERT",
                      "agentName": "Product Performance agent",
                      "metricHighlights": [
                        {"label": "Electronics order share", "value": "38%"},
                        {"label": "Electronics return rate", "value": "18%"},
                        {"label": "Store avg return rate", "value": "9%"}
                      ],
                      "reasons": [
                        "Electronics (38% of orders): return rate 18%, data shows 'Returned=TRUE' on 47 of 261 electronics rows",
                        "Beauty (22% of orders): return rate only 4%, significantly lower — contrast highlights Electronics risk"
                      ],
                      "strategies": [
                        "Audit top-returned Electronics SKUs and add clearer product specifications to reduce expectation mismatch",
                        "Introduce a 30-day quality guarantee pilot for Electronics to reduce return-driven revenue loss"
                      ],
                      "sourceColumns": ["product_category", "is_returned", "total_revenue"]
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
                - insights: generate 3-5 cards. Titles use actual column values and real numbers from the data.
                - badge: "ALERT" (critical risk/drop), "RISK" (warning signal), "OPPORTUNITY" (growth gap/win), "INFO" (notable pattern)
                - agentName: name after the subject analysed (e.g. "Product Performance agent", "Pricing Strategy agent")
                - metricHighlights: exactly 3 chips — numbers/values DIRECTLY visible in the data rows provided
                - reasons: exactly 2-3 reasons citing actual column values + the specific number from the data
                - strategies: exactly 2-3 actionable strategies that follow logically from the cited data
                - sourceColumns: list of column names from the data that this insight is based on
                - impactLevel: "HIGH", "MEDIUM", "LOW", or "POSITIVE"
                - investigations: 2 areas needing deeper analysis hinted at by the data
                - anomalies: up to 3 statistical outliers visible in the data. Return [] if none.
                - followUpQuestions: 4 natural follow-up questions that could be answered by querying the same data
                - reasoning: 1-2 sentences describing what tables/columns were analysed
                - confidence: 0-100 reflecting how directly the data supports the insights (penalise when data is sparse)
                - NEVER invent category names, group names, or labels not present verbatim in the data
                - NEVER say "I cannot analyse this" — always find something meaningful in what is provided
                """;
    }

    private String buildUserPrompt(
            String clientId,
            JsonNode catalogueNode,
            List<KpiDetectorService.ColumnHints> allHints,
            List<CollectedData> collected
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLIENT: ").append(clientId).append("\n\n");

        sb.append("SCHEMA OVERVIEW\n===============\n");
        for (KpiDetectorService.ColumnHints hints : allHints) {
            Map<String, EnrichedColInfo> enriched = buildEnrichedMap(catalogueNode, hints.tableName());
            sb.append("Table: ").append(hints.tableName()).append("\n");
            if (hints.dateCol() != null)
                sb.append("  Date column: ").append(hints.dateCol()).append("\n");
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
            if (!hints.stringCols().isEmpty())
                sb.append("  Categorical columns: ").append(String.join(", ", hints.stringCols())).append("\n");
        }
        sb.append("\n");

        sb.append("COLLECTED DATA\n==============\n");
        for (CollectedData data : collected) {
            sb.append("## ").append(data.label()).append("\n");
            List<Map<String, Object>> rows = data.rows();
            int show = data.label().startsWith("Sample rows") ? Math.min(rows.size(), RAW_SAMPLE_SHOW)
                                                              : Math.min(rows.size(), 30);
            if (!rows.isEmpty()) {
                sb.append(String.join(" | ", rows.get(0).keySet())).append("\n");
                sb.append("-".repeat(60)).append("\n");
            }
            for (int i = 0; i < show; i++) {
                sb.append(String.join(" | ", rows.get(i).values().stream()
                        .map(v -> v == null ? "null" : v.toString()).toList())).append("\n");
            }
            if (rows.size() > show)
                sb.append("... (").append(rows.size() - show).append(" more rows)\n");
            sb.append("\n");
        }

        sb.append("Based on ALL the above data, generate the dashboard insight JSON. ");
        sb.append("Analyse patterns, trends, distributions, and anomalies across every table shown. ");
        sb.append("Be specific — use actual values from the data, not generic statements.");
        return sb.toString();
    }

    private void parseSynthesisResponse(String llmResponse, AgentDashboardResult result) {
        try {
            JsonNode root = objectMapper.readTree(llmResponse);

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
                for (JsonNode h : n.path("metricHighlights"))
                    highlights.add(new AgentDashboardResult.MetricHighlight(
                            h.path("label").asText(""), h.path("value").asText("")));
                card.setMetricHighlights(highlights);

                List<String> reasons = new ArrayList<>();
                for (JsonNode r : n.path("reasons")) reasons.add(r.asText());
                card.setReasons(reasons);

                List<String> strategies = new ArrayList<>();
                for (JsonNode s : n.path("strategies")) strategies.add(s.asText());
                card.setStrategies(strategies);

                // Source columns — which actual DB columns back this insight
                List<String> sourceCols = new ArrayList<>();
                for (JsonNode s : n.path("sourceColumns")) sourceCols.add(s.asText());
                card.setSourceColumns(sourceCols);

                insights.add(card);
            }
            result.setInsights(insights);

            List<AgentDashboardResult.Investigation> investigations = new ArrayList<>();
            for (JsonNode n : root.path("investigations"))
                investigations.add(new AgentDashboardResult.Investigation(
                        n.path("title").asText(""), n.path("description").asText(""), "SUGGESTED"));
            result.setInvestigations(investigations);

            List<AgentDashboardResult.Anomaly> anomalies = new ArrayList<>();
            for (JsonNode n : root.path("anomalies"))
                anomalies.add(new AgentDashboardResult.Anomaly(
                        n.path("metric").asText(""), n.path("description").asText(""),
                        n.path("changePercent").asDouble(0), n.path("direction").asText("UP")));
            result.setAnomalies(anomalies);

            List<String> questions = new ArrayList<>();
            for (JsonNode n : root.path("followUpQuestions")) questions.add(n.asText());
            result.setFollowUpQuestions(questions);

            result.setReasoning(root.path("reasoning").asText("Analysis complete."));
            result.setConfidence(root.path("confidence").asInt(75));

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQL helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildTableRef(String tableName, String tableSchema, String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
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

    /** Raw sample: real rows for LLM reasoning — works for any data type. */
    private String buildRawSampleSql(String tableRef, String dateCol, String provider) {
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);
        if (dateCol != null) {
            String dateRef = isBQ ? "`" + dateCol + "`" : dateCol;
            return String.format("SELECT * FROM %s ORDER BY %s DESC LIMIT %d",
                    tableRef, dateRef, RAW_SAMPLE_LIMIT);
        }
        return String.format("SELECT * FROM %s LIMIT %d", tableRef, RAW_SAMPLE_LIMIT);
    }

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

    // ─────────────────────────────────────────────────────────────────────────
    // Query execution
    // ─────────────────────────────────────────────────────────────────────────

    private void safeExecute(String sql, String label, TableContext ctx,
                              List<CollectedData> out) {
        try {
            List<Map<String, Object>> rows;
            if (ctx.useBQ() && ctx.bqCfg().isPresent()) {
                var c = ctx.bqCfg().get();
                rows = bigQueryConnectorService.executeSelect(
                        c.projectId(), c.serviceAccountJson(), c.location(), c.dataset(), sql);
            } else if (ctx.useSF() && ctx.sfCfg().isPresent()) {
                var c = ctx.sfCfg().get();
                rows = snowflakeConnectorService.executeSelect(
                        c.account(), c.warehouse(), c.database(),
                        c.schema(), c.username(), c.password(), sql);
            } else {
                rows = jdbcTemplate.queryForList(sql);
            }
            if (rows != null && !rows.isEmpty()) {
                out.add(new CollectedData(label, sql, rows));
            }
        } catch (Exception e) {
            System.out.printf("[Orchestrator] Query failed [%s]: %s%n", label, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Insight card persistence
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retires stale cards, saves the new batch, and tags each InsightCard DTO with
     * the DB-assigned UUID so the frontend can call /evidence immediately.
     */
    private void persistInsightsAndTag(String clientId,
                                        List<AgentDashboardResult.InsightCard> insights,
                                        int confidence,
                                        String evidenceDigest) {
        LocalDateTime now     = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(CARD_TTL_DAYS);

        int retired = insightCardRepository.retireAllPendingCards(clientId);
        System.out.printf("[Orchestrator] Retired %d old cards for %s before new batch%n",
                retired, clientId);

        List<InsightCardEntity> entities = new ArrayList<>();
        for (AgentDashboardResult.InsightCard card : insights) {
            InsightCardEntity e = new InsightCardEntity();
            e.setClientId(clientId);
            e.setTitle(card.getTitle());
            e.setDescription(card.getDescription());
            e.setImpactLevel(card.getImpactLevel());
            e.setBadge(card.getBadge());
            e.setAgentName(card.getAgentName());
            e.setConfidence(confidence);
            e.setMetricHighlights(toJson(card.getMetricHighlights()));
            e.setReasons(toJson(card.getReasons()));
            e.setStrategies(toJson(card.getStrategies()));
            e.setSourceColumns(toJson(card.getSourceColumns()));
            e.setRawEvidence(evidenceDigest);
            e.setStatus("AWAITING_CONFIRMATION");
            e.setGeneratedAt(now);
            e.setExpiresAt(expires);
            entities.add(e);
        }

        List<InsightCardEntity> saved = insightCardRepository.saveAll(entities);

        // Tag each DTO with the DB UUID so the frontend can fetch /evidence immediately
        for (int i = 0; i < insights.size() && i < saved.size(); i++) {
            insights.get(i).setId(saved.get(i).getId().toString());
        }

        System.out.printf("[Orchestrator] Persisted %d insight cards for %s%n",
                saved.size(), clientId);
    }

    /**
     * Builds a compact evidence digest from collected data — the first 5 rows of each query.
     * Stored alongside insight cards so users can verify "what data did the AI see?"
     */
    private String buildEvidenceDigest(List<CollectedData> collected) {
        try {
            java.util.List<java.util.Map<String, Object>> digest = new ArrayList<>();
            for (CollectedData cd : collected) {
                int show = Math.min(cd.rows().size(), 5);
                java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("query", cd.label());
                entry.put("sql",   cd.sql());
                entry.put("rows",  cd.rows().subList(0, show));
                entry.put("totalRows", cd.rows().size());
                digest.add(entry);
            }
            return objectMapper.writeValueAsString(digest);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Manually retire all pending cards — used to clear accumulated stale cards. */
    public int clearPendingInsights(String clientId) {
        return insightCardRepository.retireAllPendingCards(clientId);
    }

    /**
     * Generates 4-5 follow-up questions from the current active insight cards.
     * Called by the persisted feed endpoint so suggested questions are always shown,
     * not just immediately after a live Refresh.
     */
    public List<String> generateFollowUpFromActiveInsights(String clientId) {
        List<InsightCardEntity> cards = getActiveInsights(clientId);
        if (cards.isEmpty()) return List.of(
                "Show me a summary of recent orders",
                "What are the top-performing products?",
                "What is the overall return rate?",
                "Which customers have the highest revenue?"
        );

        // Build compact insight summary for the LLM
        StringBuilder context = new StringBuilder();
        int limit = Math.min(cards.size(), 6);
        for (int i = 0; i < limit; i++) {
            InsightCardEntity c = cards.get(i);
            context.append("- ").append(c.getTitle());
            if (c.getDescription() != null && !c.getDescription().isBlank())
                context.append(": ").append(c.getDescription());
            context.append("\n");
        }

        String prompt =
                "Based on these business insights:\n\n" + context +
                "\nGenerate exactly 4 short, specific follow-up questions a business analyst " +
                "would naturally want to ask next. Questions should be directly related to " +
                "the insights above and answerable from the same data.\n" +
                "Respond with JSON: {\"questions\": [\"...\", \"...\", \"...\", \"...\"]}";

        try {
            String resp = openAiClient.chat(
                    "You generate concise business follow-up questions. Respond only with JSON.",
                    prompt);
            List<String> questions = new ArrayList<>();
            for (JsonNode n : objectMapper.readTree(resp).path("questions"))
                questions.add(n.asText());
            return questions.isEmpty() ? fallbackQuestions() : questions;
        } catch (Exception e) {
            return fallbackQuestions();
        }
    }

    private List<String> fallbackQuestions() {
        return List.of(
                "What are the top 10 customers by revenue?",
                "Show me the return rate by product category",
                "Which payment methods are most commonly used?",
                "What is the trend in orders over the last 30 days?"
        );
    }

    /** Record used by the evidence endpoint. */
    public record EvidenceResult(String sourceColumns, String rawEvidence) {}

    /**
     * Returns the raw data the AI saw when generating a specific insight card.
     * Returns empty if the card is not found or belongs to a different client.
     */
    public java.util.Optional<EvidenceResult> getInsightEvidence(java.util.UUID cardId, String clientId) {
        return insightCardRepository.findById(cardId)
                .filter(c -> clientId == null || clientId.equals(c.getClientId()))
                .map(c -> new EvidenceResult(
                        c.getSourceColumns() != null ? c.getSourceColumns() : "[]",
                        c.getRawEvidence()   != null ? c.getRawEvidence()   : "[]"
                ));
    }

    /**
     * Returns all non-expired insight cards for a tenant (used by the feed endpoint).
     * Auto-expires stale cards before returning.
     */
    public List<InsightCardEntity> getActiveInsights(String clientId) {
        insightCardRepository.expireOldCards(clientId, LocalDateTime.now());
        // Only unread inbox items — COMPLETED (mark as read) and DECLINED stay hidden
        return insightCardRepository
                .findByClientIdAndStatusOrderByGeneratedAtDesc(clientId, "AWAITING_CONFIRMATION");
    }

    /**
     * Updates a card's status to DECLINED or COMPLETED.
     * Returns true if the update was applied.
     */
    public boolean updateInsightStatus(java.util.UUID cardId, String clientId, String status) {
        if (!List.of("DECLINED", "COMPLETED").contains(status)) return false;
        int updated = insightCardRepository.updateStatus(cardId, clientId, status, LocalDateTime.now());
        if (updated > 0) {
            // Record in decision memory so future confidence scoring can learn from this
            insightCardRepository.findById(cardId).ifPresent(
                    card -> decisionMemoryService.record(card, status));
        }
        return updated > 0;
    }

    private String toJson(Object value) {
        if (value == null) return "[]";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KPI card fallback (for tables with no enriched metric columns)
    // ─────────────────────────────────────────────────────────────────────────

    /** Builds basic KPI cards from trend data when KpiPerformanceAgent found no results. */
    private List<AgentDashboardResult.KpiCard> legacyKpiCards(List<CollectedData> collected) {
        List<AgentDashboardResult.KpiCard> cards = new ArrayList<>();
        for (CollectedData data : collected) {
            if (!data.label().startsWith("Trend:")) continue;
            List<Map<String, Object>> rows = data.rows();
            if (rows.size() < 2) continue;

            String metricKey = rows.get(0).keySet().stream()
                    .filter(k -> isNumeric(rows.get(0).get(k))).findFirst().orElse(null);
            if (metricKey == null) continue;

            int half    = Math.max(1, rows.size() / 2);
            double curr = average(rows.subList(0, half), metricKey);
            double prev = average(rows.subList(half, rows.size()), metricKey);
            double chg  = prev == 0 ? 0 : ((curr - prev) / Math.abs(prev)) * 100.0;
            String dir  = chg > 0.5 ? "UP" : chg < -0.5 ? "DOWN" : "FLAT";

            String name = data.label().replace("Trend: ", "").replace(" over time", "");
            cards.add(new AgentDashboardResult.KpiCard(
                    name, formatValue(curr), curr, prev, Math.round(chg * 10.0) / 10.0, dir));
            if (cards.size() >= 4) break;
        }
        return cards;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private void emptyResult(AgentDashboardResult result, String error) {
        result.setErrorMessage(error);
        result.setConfidence(0);
        result.setKpiCards(List.of());
        result.setInsights(List.of());
        result.setInvestigations(List.of());
        result.setAnomalies(List.of());
        result.setFollowUpQuestions(List.of());
    }

    private double average(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToDouble(r -> {
            Object v = r.get(key);
            if (v instanceof Number n) return n.doubleValue();
            try { return v != null ? Double.parseDouble(v.toString()) : 0; }
            catch (NumberFormatException e) { return 0; }
        }).average().orElse(0);
    }

    private boolean isNumeric(Object v) {
        if (v instanceof Number) return true;
        if (v == null) return false;
        try { Double.parseDouble(v.toString()); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private String formatValue(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (v >= 1_000)     return String.format("%.1fK", v / 1_000);
        return String.format("%.1f", v);
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
