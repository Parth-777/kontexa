package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.KpiDetectorService;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.entity.DailyMetricRollupEntity;
import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.repository.DailyMetricRollupRepository;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds daily metric rollups in Postgres for eligible LARGE fact tables.
 */
@Service
public class MetricRollupService {

    private static final int ROLLUP_METRICS = 2;
    private static final int ROLLUP_DIMS    = 3;

    private final CatalogueSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final KpiDetectorService kpiDetector;
    private final TenantCloudConnectionService cloudConnectionService;
    private final JdbcTemplate jdbcTemplate;
    private final TableScalePolicy tableScalePolicy;
    private final AnalysisWindowFactory analysisWindowFactory;
    private final ScaleAwareQueryExecutor queryExecutor;
    private final DailyMetricRollupRepository rollupRepository;
    private final ScaleProperties properties;

    public MetricRollupService(
            CatalogueSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper,
            KpiDetectorService kpiDetector,
            TenantCloudConnectionService cloudConnectionService,
            JdbcTemplate jdbcTemplate,
            TableScalePolicy tableScalePolicy,
            AnalysisWindowFactory analysisWindowFactory,
            ScaleAwareQueryExecutor queryExecutor,
            DailyMetricRollupRepository rollupRepository,
            ScaleProperties properties
    ) {
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.kpiDetector = kpiDetector;
        this.cloudConnectionService = cloudConnectionService;
        this.jdbcTemplate = jdbcTemplate;
        this.tableScalePolicy = tableScalePolicy;
        this.analysisWindowFactory = analysisWindowFactory;
        this.queryExecutor = queryExecutor;
        this.rollupRepository = rollupRepository;
        this.properties = properties;
    }

    public void buildRollupsForClient(String clientId) {
        if (!properties.isRollupEnabled()) return;

        try {
            String snapshotJson = snapshotRepository.findByClientId(clientId)
                    .map(s -> s.getCatalogueJson())
                    .orElseThrow(() -> new IllegalStateException(
                            "No approved catalogue found for client: " + clientId));
            JsonNode catalogueNode = objectMapper.readTree(snapshotJson);
            String provider = cloudConnectionService.getProvider(clientId);

            Optional<TenantCloudConnectionService.BigQueryConfig> bqCfg = Optional.empty();
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg = Optional.empty();
            if ("bigquery".equalsIgnoreCase(provider)) {
                bqCfg = cloudConnectionService.getBigQueryConfig(clientId);
            } else if ("snowflake".equalsIgnoreCase(provider)) {
                sfCfg = cloudConnectionService.getSnowflakeConfig(clientId);
            }

            boolean useBQ = bqCfg.isPresent();
            boolean useSF = sfCfg.isPresent();

            for (JsonNode tableNode : catalogueNode.path("tables")) {
                if (!isRollupEligible(tableNode)) continue;

                KpiDetectorService.ColumnHints rawHints = kpiDetector.classifyColumns(tableNode);
                if (rawHints.dateCol() == null || rawHints.numericCols().isEmpty()) continue;

                long rowCount = tableNode.path("rowCount").asLong(0);
                ScaleTier tier = tableScalePolicy.tier(rowCount);
                if (tier != ScaleTier.LARGE) continue;

                List<String> metrics = ColumnSelector.selectMetrics(
                        rawHints.numericCols(), Map.of(), ROLLUP_METRICS);
                List<String> dims = ColumnSelector.selectDimensions(
                        rawHints.stringCols(), tier, ROLLUP_DIMS);

                KpiDetectorService.ColumnHints hints = new KpiDetectorService.ColumnHints(
                        rawHints.tableName(), rawHints.tableSchema(),
                        rawHints.dateCol(), dims, metrics);

                String tableRef = buildTableRef(hints.tableName(), hints.tableSchema(), provider);
                TableContext probeCtx = new TableContext(
                        clientId, hints, Map.of(), tableRef, provider,
                        useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                        tier, rowCount, AnalysisWindow.unrestricted(),
                        AnalysisRunContext.unlimited(), tableNode.path("tableRole").asText(null));
                AnalysisWindow window = analysisWindowFactory.forTable(hints, Map.of(), tier, probeCtx);

                TableContext ctx = new TableContext(
                        clientId, hints, Map.of(), tableRef, provider,
                        useBQ, useSF, bqCfg, sfCfg, jdbcTemplate,
                        tier, rowCount, window, AnalysisRunContext.unlimited(),
                        tableNode.path("tableRole").asText(null));

                buildRollups(ctx);
            }
        } catch (Exception e) {
            System.out.printf("[MetricRollup] Failed for %s: %s%n", clientId, e.getMessage());
        }
    }

    @Transactional
    public void buildRollups(TableContext ctx) {
        String clientId = ctx.clientId();
        String tableName = ctx.hints().tableName();
        rollupRepository.deleteByClientIdAndTableName(clientId, tableName);

        String dateRef = AgentSqlHelper.qualify(ctx.hints().dateCol(), ctx.provider());
        String window  = AgentSqlHelper.windowClause(ctx);
        LocalDateTime builtAt = LocalDateTime.now();

        for (String metric : ctx.hints().numericCols()) {
            String metricRef = AgentSqlHelper.qualify(metric, ctx.provider());
            EnrichedColInfo metricInfo = ctx.enriched().get(metric.toLowerCase());
            String agg = metricInfo != null && !"NONE".equals(metricInfo.aggregationMethod())
                    ? metricInfo.aggregationMethod() : "SUM";
            String aggExpr = switch (agg) {
                case "COUNT" -> "COUNT(" + metricRef + ")";
                case "AVG"   -> "AVG(" + metricRef + ")";
                default      -> "SUM(" + metricRef + ")";
            };

            String sql = String.format(
                    "SELECT DATE(%s) AS metric_date, %s AS metric_value FROM %s " +
                    "GROUP BY metric_date ORDER BY metric_date",
                    dateRef, aggExpr, AgentSqlHelper.fromWithPredicates(ctx, dateRef + " IS NOT NULL"));

            List<Map<String, Object>> rows = queryExecutor.execute(
                    sql, "ROLLUP: " + tableName + "." + metric, ctx);

            List<DailyMetricRollupEntity> entities = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object dateVal = row.get("metric_date");
                if (dateVal == null) continue;
                LocalDate metricDate = LocalDate.parse(dateVal.toString().substring(0, 10));

                DailyMetricRollupEntity entity = new DailyMetricRollupEntity();
                entity.setClientId(clientId);
                entity.setTableName(tableName);
                entity.setMetricDate(metricDate);
                entity.setMetricName(metric);
                entity.setMetricValue(toDouble(row.get("metric_value")));
                entity.setAggType(agg);
                entity.setBuiltAt(builtAt);
                entities.add(entity);
            }

            if (!entities.isEmpty()) {
                rollupRepository.saveAll(entities);
            }
        }

        System.out.printf("[MetricRollup] Built %d rollup rows for %s.%s%n",
                rollupRepository.countByClientIdAndTableName(clientId, tableName),
                clientId, tableName);
    }

    private boolean isRollupEligible(JsonNode tableNode) {
        if (!"FACT".equalsIgnoreCase(tableNode.path("tableRole").asText(""))) return false;
        long rowCount = tableNode.path("rowCount").asLong(0);
        return tableScalePolicy.tier(rowCount) == ScaleTier.LARGE;
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value.toString());
    }

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
}
