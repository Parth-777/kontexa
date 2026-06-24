package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.agents.AnomalyAgent;
import com.example.BACKEND.catalogue.agent.agents.CrossTableAgent;
import com.example.BACKEND.catalogue.agent.agents.DistributionAgent;
import com.example.BACKEND.catalogue.agent.agents.KpiPerformanceAgent;
import com.example.BACKEND.catalogue.agent.agents.ForecastingAgent;
import com.example.BACKEND.catalogue.agent.agents.RootCauseAnalysisAgent;
import com.example.BACKEND.catalogue.agent.agents.TrendAgent;
import com.example.BACKEND.catalogue.agent.scale.AnalysisRunContext;
import com.example.BACKEND.catalogue.agent.scale.AnalysisWindow;
import com.example.BACKEND.catalogue.agent.scale.AnalysisWindowFactory;
import com.example.BACKEND.catalogue.agent.scale.ColumnSelector;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import com.example.BACKEND.catalogue.agent.scale.ScaleProperties;
import com.example.BACKEND.catalogue.agent.scale.ScaleTier;
import com.example.BACKEND.catalogue.agent.executive.ColumnDiscoveryPlanner;
import com.example.BACKEND.catalogue.agent.executive.ExecutiveMetricPack;
import com.example.BACKEND.catalogue.agent.executive.ExecutiveNarrator;
import com.example.BACKEND.catalogue.agent.executive.MeetingReadyInsightPolisher;
import com.example.BACKEND.catalogue.agent.executive.InsightCandidate;
import com.example.BACKEND.catalogue.agent.executive.LensInsightCoordinator;
import com.example.BACKEND.catalogue.agent.executive.MaterialityRanker;
import com.example.BACKEND.catalogue.agent.executive.SignalSummaryBuilder;
import com.example.BACKEND.catalogue.charts.ChartPanelItem;
import com.example.BACKEND.catalogue.charts.ChartPanelResponse;
import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.charts.InsightChartMapper;
import com.example.BACKEND.catalogue.agent.scale.TableScalePolicy;
import com.example.BACKEND.catalogue.entity.AgentRunEntity;
import com.example.BACKEND.catalogue.entity.SignalEntity;
import com.example.BACKEND.catalogue.entity.InsightCardEntity;
import com.example.BACKEND.catalogue.repository.AgentRunRepository;
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
    private final ExecutiveMetricPack       executiveMetricPack;
    private final SignalSummaryBuilder      signalSummaryBuilder;
    private final LensInsightCoordinator    lensCoordinator;
    private final MaterialityRanker         materialityRanker;
    private final ExecutiveNarrator           executiveNarrator;
    private final MeetingReadyInsightPolisher meetingReadyPolisher;
    private final InsightEvidenceValidator  evidenceValidator;
    private final InsightChartMapper        insightChartMapper;
    private final TableScalePolicy          tableScalePolicy;
    private final AnalysisWindowFactory     analysisWindowFactory;
    private final ScaleAwareQueryExecutor   scaleQueryExecutor;
    private final TableProfileService       tableProfileService;
    private final GeneralDiscoveryAgent     generalDiscoveryAgent;
    private final RevenueModelAgent         revenueModelAgent;
    private final ScaleProperties           scaleProperties;
    private final AgentRunRepository        agentRunRepository;

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
            ExecutiveMetricPack         executiveMetricPack,
            SignalSummaryBuilder        signalSummaryBuilder,
            LensInsightCoordinator      lensCoordinator,
            MaterialityRanker           materialityRanker,
            ExecutiveNarrator           executiveNarrator,
            MeetingReadyInsightPolisher meetingReadyPolisher,
            InsightEvidenceValidator  evidenceValidator,
            InsightChartMapper          insightChartMapper,
            TableScalePolicy            tableScalePolicy,
            AnalysisWindowFactory       analysisWindowFactory,
            ScaleAwareQueryExecutor     scaleQueryExecutor,
            TableProfileService         tableProfileService,
            GeneralDiscoveryAgent       generalDiscoveryAgent,
            RevenueModelAgent           revenueModelAgent,
            ScaleProperties             scaleProperties,
            AgentRunRepository          agentRunRepository
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
        this.executiveMetricPack       = executiveMetricPack;
        this.signalSummaryBuilder      = signalSummaryBuilder;
        this.lensCoordinator           = lensCoordinator;
        this.materialityRanker         = materialityRanker;
        this.executiveNarrator         = executiveNarrator;
        this.meetingReadyPolisher      = meetingReadyPolisher;
        this.evidenceValidator         = evidenceValidator;
        this.insightChartMapper        = insightChartMapper;
        this.tableScalePolicy          = tableScalePolicy;
        this.analysisWindowFactory     = analysisWindowFactory;
        this.scaleQueryExecutor        = scaleQueryExecutor;
        this.tableProfileService       = tableProfileService;
        this.generalDiscoveryAgent    = generalDiscoveryAgent;
        this.revenueModelAgent        = revenueModelAgent;
        this.scaleProperties          = scaleProperties;
        this.agentRunRepository        = agentRunRepository;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    public AgentDashboardResult analyse(String clientId) {
        AgentDashboardResult result = new AgentDashboardResult();
        result.setLastUpdated(Instant.now().toString());

        LocalDateTime runStarted = LocalDateTime.now();
        AnalysisRunContext runContext = new AnalysisRunContext(
                scaleProperties.getSchedulerMaxQueriesPerTenant(),
                scaleProperties.getGuardBigqueryMaxBytesPerRun());
        String runStatus = "SUCCESS";
        String runError  = null;

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
            List<SignalEntity>                           signals        = new ArrayList<>();

            // 3b. Signal detection — what changed since last run (executives care first)
            try {
                signals = signalSummaryBuilder.detectAndSummarize(clientId, catalogueNode, collected);
                System.out.printf("[Orchestrator] %d material signal(s) for %s%n", signals.size(), clientId);
            } catch (Exception e) {
                System.out.printf("[Orchestrator] Signal detection skipped: %s%n", e.getMessage());
            }

            // 4. Per-table agent loop
            for (JsonNode tableNode : catalogueNode.path("tables")) {
                if (runContext.isBudgetExceeded()) {
                    System.out.printf("[Orchestrator] Query budget exceeded for %s — stopping table loop%n", clientId);
                    break;
                }

                KpiDetectorService.ColumnHints rawHints = kpiDetector.classifyColumns(tableNode);
                if (rawHints.tableName().isBlank()) continue;

                long rowCount = tableNode.path("rowCount").asLong(0);
                ScaleTier tier = tableScalePolicy.tier(rowCount);
                String tableRole = tableNode.path("tableRole").asText(null);

                if (tier == ScaleTier.LARGE && tableScalePolicy.requireDateWindow(tier)
                        && rawHints.dateCol() == null) {
                    System.out.printf("[Orchestrator] Skipping LARGE table %s — no date column%n",
                            rawHints.tableName());
                    continue;
                }

                Map<String, EnrichedColInfo> enriched = buildEnrichedMap(catalogueNode, rawHints.tableName());

                String tableRef = buildTableRef(rawHints.tableName(), rawHints.tableSchema(), provider);
                TableContext windowProbeCtx = new TableContext(
                        clientId,
                        new KpiDetectorService.ColumnHints(
                                rawHints.tableName(), rawHints.tableSchema(),
                                rawHints.dateCol(), rawHints.stringCols(), rawHints.numericCols()),
                        enriched, tableRef, provider, useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                        tier, rowCount, AnalysisWindow.unrestricted(), runContext, tableRole);
                AnalysisWindow window = analysisWindowFactory.forTable(rawHints, enriched, tier, windowProbeCtx);

                List<String> metrics = ColumnSelector.selectMetrics(
                        rawHints.numericCols(), enriched, tableScalePolicy.properties().maxMetrics(tier));
                List<String> dims = ColumnSelector.selectDimensions(
                        rawHints.stringCols(), tier, tableScalePolicy.properties().maxDimensions(tier));
                List<String> distributionDims = ColumnSelector.selectDimensionsForDistribution(
                        rawHints.stringCols(), tier,
                        tableScalePolicy.properties().getInsightDistributionDims());

                List<String> scanDims = ColumnDiscoveryPlanner.pickDimensions(
                        rawHints, tableScalePolicy.properties().getInsightScanDimensions());
                var discoveryPlan = ColumnDiscoveryPlanner.plan(
                        rawHints, enriched, 4, scanDims.size());
                List<String> scanMetrics = new ArrayList<>(metrics);
                for (String m : discoveryPlan.revenueMetrics()) {
                    if (!scanMetrics.contains(m)) scanMetrics.add(m);
                }
                for (String m : discoveryPlan.behaviorMetrics()) {
                    if (!scanMetrics.contains(m) && scanMetrics.size() < 5) scanMetrics.add(m);
                }

                KpiDetectorService.ColumnHints hints = new KpiDetectorService.ColumnHints(
                        rawHints.tableName(), rawHints.tableSchema(),
                        rawHints.dateCol(), dims, metrics);

                KpiDetectorService.ColumnHints scanHints = new KpiDetectorService.ColumnHints(
                        rawHints.tableName(), rawHints.tableSchema(),
                        rawHints.dateCol(), scanDims, scanMetrics);

                tables.add(hints.tableName());
                allHints.add(hints);

                TableContext ctx = new TableContext(
                        clientId, hints, enriched, tableRef, provider,
                        useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                        tier, rowCount, window, runContext, tableRole
                );
                TableContext scanCtx = new TableContext(
                        clientId, scanHints, enriched, tableRef, provider,
                        useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                        tier, rowCount, window, runContext, tableRole
                );

                System.out.printf("[Orchestrator] Table %s tier=%s rowCount=%d metrics=%d dims=%d scanDims=%d%n",
                        hints.tableName(), tier, rowCount, metrics.size(), dims.size(), scanDims.size());

                if (tableScalePolicy.allowRawSample(tier)) {
                    safeExecute(buildRawSampleSql(tableRef, hints.dateCol(), provider),
                            "Sample rows from " + hints.tableName(), ctx, collected);
                } else {
                    collected.addAll(tableProfileService.collectProfile(ctx));
                }

                // Executive decision metrics (MoM delta, contribution, concentration)
                collected.addAll(executiveMetricPack.collect(scanCtx));

                // Revenue model — sources, weak areas, trends, factor contribution
                collected.addAll(revenueModelAgent.collect(
                        scanCtx, rawHints, scaleProperties.getRevenueMaxProbesPerTable()));

                // General discovery — corridors, zone performance, concentration
                collected.addAll(generalDiscoveryAgent.collect(
                        scanCtx, rawHints, scaleProperties.getDiscoveryMaxProbesPerTable()));

                // TrendAgent — time-series + dimension breakdowns for metric columns
                collected.addAll(trendAgent.collectData(ctx));

                // 3c. DistributionAgent — profile up to N dimensions for insight diversity
                KpiDetectorService.ColumnHints distHints = new KpiDetectorService.ColumnHints(
                        rawHints.tableName(), rawHints.tableSchema(),
                        rawHints.dateCol(), distributionDims, metrics);
                TableContext distCtx = new TableContext(
                        clientId, distHints, enriched, tableRef, provider,
                        useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                        tier, rowCount, window, runContext, tableRole);
                collected.addAll(distributionAgent.collectData(distCtx));

                // 3d. KpiPerformanceAgent — current vs prior period + KPI cards
                KpiPerformanceAgent.KpiResult kpiResult = kpiPerformanceAgent.collectData(ctx);
                collected.addAll(kpiResult.collected());
                kpiCards.addAll(kpiResult.cards());

                // 3e. AnomalyAgent — Z-score statistical outlier detection
                List<AgentDashboardResult.Anomaly> tableAnomalies = anomalyAgent.detectAnomalies(ctx);
                anomalies.addAll(tableAnomalies);

                // 3f. RootCauseAnalysisAgent — ReAct multi-step drill-down on high anomalies
                if (!tableAnomalies.isEmpty()) {
                    if (tableScalePolicy.allowRootCauseReAct(tier)) {
                        rootCauseCards.addAll(rootCauseAgent.investigate(tableAnomalies, ctx, catalogueNode));
                    } else if (tier == ScaleTier.LARGE) {
                        rootCauseCards.addAll(
                                rootCauseAgent.investigateWithTemplates(tableAnomalies, ctx, catalogueNode));
                    }
                }
            }

            if (runContext.isBudgetExceeded()) {
                // Budget exhaustion is treated as partial-success, not a user-facing error.
                // We keep run metadata for observability but return collected insights/cards.
                runStatus = "PARTIAL";
                runError = "Query budget reached after " + runContext.queriesRun() + " queries.";
                result.setErrorMessage(null);
            }

            // 5. CrossTableAgent — fact × dimension join analysis
            collected.addAll(crossTableAgent.collectData(
                    clientId, catalogueNode, provider,
                    useBQ, useSF, bqCfg, sfCfg, jdbcTemplate, runContext
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
                String hint = maxRowCountInCatalogue(catalogueNode) > 0
                        ? " Queries returned no rows — check the date column is detected and BigQuery can read MAX/recent dates on the table."
                        : " Check that the data source is accessible and contains rows.";
                result.setErrorMessage(
                        "No data returned from " + String.join(", ", tables) + "." + hint);
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

            // 8. Lens candidates → materiality rank → executive narration
            List<InsightCandidate> allCandidates = lensCoordinator.generateAll(
                    collected, kpiCards, anomalies, signals);
            List<InsightCandidate> topCandidates = materialityRanker.selectTop(
                    allCandidates, clientId, 75);
            System.out.printf("[Orchestrator] Insight candidates: %d total → %d selected (min %d)%n",
                    allCandidates.size(), topCandidates.size(), MaterialityRanker.MIN_INSIGHT_CARDS);

            if (!topCandidates.isEmpty()) {
                List<AgentDashboardResult.InsightCard> narrated =
                        executiveNarrator.narrateCandidates(clientId, topCandidates, collected);
                List<AgentDashboardResult.InsightCard> verified =
                        evidenceValidator.filterSupported(narrated, collected);
                if (verified.isEmpty()) {
                    System.out.printf(
                            "[Orchestrator] %d narrated card(s) failed evidence check — using programmatic cards%n",
                            narrated != null ? narrated.size() : 0);
                    verified = executiveNarrator.programmaticCards(topCandidates);
                }
                result.setInsights(verified);
                result.setReasoning("Executive pipeline: " + topCandidates.size()
                        + " verified candidates from " + allCandidates.size() + " lens findings.");
                result.setConfidence(85);
            } else {
                synthesizeFallback(clientId, catalogueNode, allHints, collected, kpiCards, anomalies, result);
                if (result.getInsights() != null && !result.getInsights().isEmpty()) {
                    result.setInsights(evidenceValidator.filterSupported(result.getInsights(), collected));
                }
            }

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

            // 10b. Meeting-ready polish — slide headlines, distinct So What, specific strategies
            if (result.getInsights() != null && !result.getInsights().isEmpty()) {
                meetingReadyPolisher.polish(clientId, result.getInsights(), collected);
            }

            // 10d. Attach chart specs (backend contract; frontend renders)
            if (result.getInsights() != null && !result.getInsights().isEmpty()) {
                attachCharts(result.getInsights(), collected);
            }

            // 10c. Leadership brief — board-level summary from polished cards (not raw claims)
            result.setDailyBrief(executiveNarrator.buildDailyBrief(
                    clientId,
                    leadershipBriefSources(result.getInsights()),
                    topCandidates,
                    collected));

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
            runStatus = "FAILED";
            runError = e.getMessage();
            emptyResult(result, "No approved catalogue found. Please approve your catalogue first.");
        } catch (Exception e) {
            runStatus = "FAILED";
            runError = e.getMessage();
            emptyResult(result, "Analysis failed: " + e.getMessage());
        } finally {
            persistAgentRun(clientId, runStarted, runContext, runStatus, runError);
        }

        return result;
    }

    private void persistAgentRun(String clientId, LocalDateTime started,
                                  AnalysisRunContext runContext, String status, String error) {
        try {
            AgentRunEntity run = new AgentRunEntity();
            run.setClientId(clientId);
            run.setStartedAt(started);
            run.setFinishedAt(LocalDateTime.now());
            run.setQueriesRun(runContext.queriesRun());
            run.setBytesScanned(runContext.bytesScanned());
            run.setBudgetExceeded(runContext.isBudgetExceeded());
            run.setStatus(status);
            run.setErrorMessage(error);
            agentRunRepository.save(run);
        } catch (Exception e) {
            System.out.printf("[Orchestrator] Failed to persist agent run for %s: %s%n",
                    clientId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM synthesis  (prompts intentionally match AgentAnalysisService)
    // ─────────────────────────────────────────────────────────────────────────

    /** Fallback when no lens candidates pass materiality threshold. */
    private void synthesizeFallback(
            String clientId,
            JsonNode catalogueNode,
            List<KpiDetectorService.ColumnHints> allHints,
            List<CollectedData> collected,
            List<AgentDashboardResult.KpiCard> kpiCards,
            List<AgentDashboardResult.Anomaly> anomalies,
            AgentDashboardResult result
    ) {
        String system = buildSystemPrompt();
        String user   = buildUserPrompt(clientId, catalogueNode, allHints, collected, kpiCards, anomalies);
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
                You are the Chief Analytics Officer briefing a VP and the executive team.
                Write like a board-ready briefing: decisive, concise, numbers-first, zero jargon.

                You receive EXECUTIVE HEADLINES (pre-verified KPIs and anomalies) plus supporting query results.
                Your job: turn them into at most 3 insight cards the VP can act on in under 60 seconds each.

                Voice and structure:
                - title: "[Metric/segment] [↑/↓] [X%] — [one-line business implication]" (use real labels from data)
                - description: 1-2 sentences — what happened, so what for revenue/risk/ops, no filler
                - Lead with the biggest dollar or risk impact; deprioritize trivia
                - strategies: name an owner (Sales, Product, Finance, Ops, Marketing) + specific action + expected outcome
                - reasons: past-tense facts with numbers — "Electronics returns hit 18% vs 9% store average"

                The data can be ANY domain (retail, SaaS, logistics, HR). Never say you cannot analyse it.

                Prioritise:
                - Period-over-period moves in EXECUTIVE HEADLINES
                - Statistical anomalies and material concentration risks
                - Trends that change forecast or capacity decisions

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
                - insights: generate exactly 2-3 cards (executives ignore more). Prioritise EXECUTIVE HEADLINES.
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
            List<CollectedData> collected,
            List<AgentDashboardResult.KpiCard> kpiCards,
            List<AgentDashboardResult.Anomaly> anomalies
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("CLIENT: ").append(clientId).append("\n\n");

        sb.append(buildExecutiveHeadlines(kpiCards, anomalies, collected)).append("\n");

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

        sb.append("Generate the executive insight JSON. ");
        sb.append("Start from EXECUTIVE HEADLINES, then support with COLLECTED DATA. ");
        sb.append("Write for a VP: implication first, evidence second, action third.");
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

    private String buildExecutiveHeadlines(
            List<AgentDashboardResult.KpiCard> kpiCards,
            List<AgentDashboardResult.Anomaly> anomalies,
            List<CollectedData> collected) {
        StringBuilder sb = new StringBuilder();
        sb.append("EXECUTIVE HEADLINES\n===================\n");
        if (kpiCards != null) {
            for (var k : kpiCards) {
                sb.append("• ").append(k.getMetric()).append(" ")
                        .append(k.getDirection()).append(" ")
                        .append(k.getChangePercent()).append("%\n");
            }
        }
        if (anomalies != null) {
            for (var a : anomalies) {
                sb.append("• ANOMALY ").append(a.getMetric()).append(": ")
                        .append(a.getChangePercent()).append("%\n");
            }
        }
        for (CollectedData cd : collected) {
            if (cd.label() != null && cd.label().startsWith("SIGNALS:")) {
                sb.append("• Signals dataset attached (").append(cd.rows().size()).append(" changes)\n");
            }
            if (cd.label() != null && cd.label().startsWith("EXEC:")) {
                sb.append("• ").append(cd.label()).append("\n");
            }
        }
        return sb.toString();
    }

    private void safeExecute(String sql, String label, TableContext ctx,
                              List<CollectedData> out) {
        List<Map<String, Object>> rows = scaleQueryExecutor.execute(sql, label, ctx);
        if (rows != null && !rows.isEmpty()) {
            out.add(new CollectedData(label, sql, rows));
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
            e.setChartSpec(toJson(card.getChart()));
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

    private void attachCharts(List<AgentDashboardResult.InsightCard> insights,
                              List<CollectedData> collected) {
        for (AgentDashboardResult.InsightCard card : insights) {
            try {
                if (card.getChart() != null) continue;
                var chart = insightChartMapper.chartFor(card, collected);
                if (chart != null) card.setChart(chart);
            } catch (Exception e) {
                System.out.printf("[Charts] Failed to map chart for '%s': %s%n",
                        card.getTitle(), e.getMessage());
            }
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
     * Charts for the sidebar panel beside the Agent Feed — one entry per insight that has a chart.
     */
    public ChartPanelResponse getChartPanel(String clientId) {
        List<InsightCardEntity> cards = getActiveInsights(clientId);
        List<ChartPanelItem> panel = new ArrayList<>();
        for (InsightCardEntity card : cards) {
            ChartSpec spec = parseChartSpec(card.getChartSpec());
            if (spec == null) continue;
            panel.add(new ChartPanelItem(
                    card.getId(),
                    card.getTitle(),
                    card.getDescription(),
                    card.getBadge(),
                    card.getAgentName(),
                    spec));
        }
        return new ChartPanelResponse(panel, cards.size(), panel.size());
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
        return updateInsightStatus(cardId, clientId, status, null);
    }

    public boolean updateInsightStatus(java.util.UUID cardId, String clientId,
                                        String status, String dismissReason) {
        if (!List.of("DECLINED", "COMPLETED").contains(status)) return false;
        int updated = insightCardRepository.updateStatus(cardId, clientId, status, LocalDateTime.now());
        if (updated > 0) {
            insightCardRepository.findById(cardId).ifPresent(card -> {
                decisionMemoryService.record(card, status);
                if ("DECLINED".equals(status) && dismissReason != null && !dismissReason.isBlank()) {
                    System.out.printf("[DecisionMemory] Dismiss reason for %s: %s%n",
                            cardId, dismissReason);
                }
            });
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

    private ChartSpec parseChartSpec(String json) {
        if (json == null || json.isBlank() || "null".equalsIgnoreCase(json.trim())) return null;
        try {
            return objectMapper.readValue(json, ChartSpec.class);
        } catch (Exception e) {
            System.out.printf("[Charts] Failed to parse chart_spec: %s%n", e.getMessage());
            return null;
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

    /** Up to 3 cards for the Leadership Brief — root cause first, skip forecasts. */
    private List<AgentDashboardResult.InsightCard> leadershipBriefSources(
            List<AgentDashboardResult.InsightCard> insights) {
        if (insights == null || insights.isEmpty()) return List.of();
        List<AgentDashboardResult.InsightCard> picked = new ArrayList<>();
        for (AgentDashboardResult.InsightCard card : insights) {
            if (picked.size() >= 3) break;
            if (card == null) continue;
            String agent = card.getAgentName() == null ? "" : card.getAgentName().toLowerCase();
            if (agent.contains("forecast")) continue;
            picked.add(card);
        }
        return picked;
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private long maxRowCountInCatalogue(JsonNode catalogueNode) {
        long max = 0;
        for (JsonNode t : catalogueNode.path("tables")) {
            max = Math.max(max, t.path("rowCount").asLong(0));
        }
        return max;
    }
}
