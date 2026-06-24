package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.scale.AnalysisRunContext;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import com.example.BACKEND.catalogue.agent.scale.TableContextFactory;
import com.example.BACKEND.catalogue.entity.SignalEntity;
import com.example.BACKEND.catalogue.repository.SignalRepository;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects meaningful changes in a tenant's data by comparing current query
 * results against stored baselines in the signals table.
 *
 * This implements the Kontexity "Signal-First" principle:
 *   Agents fire because data changed — not because a timer fired.
 *
 * For each table the service runs three checks:
 *
 *   METRIC_SHIFT        — has a metric column's average changed significantly?
 *   DISTRIBUTION_CHANGE — has a dimension column's top-value share shifted?
 *   TIME_TREND          — has the per-month record count changed significantly?
 *
 * Significance thresholds (delta % from baseline):
 *   HIGH   ≥ 10%
 *   MEDIUM  5–10%
 *   LOW     stored as baseline only (no agent trigger)
 *
 * Deduplication: same signal type on same column is suppressed if it already
 * fired within the last 6 hours (prevents alert storms).
 *
 * First run per column: stores the current value as baseline only (no trigger).
 * Subsequent runs: compares current vs stored baseline and emits when significant.
 */
@Service
public class SignalDetectionService {

    private static final double HIGH_THRESHOLD   = 10.0;
    private static final double MEDIUM_THRESHOLD =  5.0;
    private static final int    DEDUP_HOURS      =  6;

    private final SignalRepository              signalRepo;
    private final KpiDetectorService            kpiDetector;
    private final TenantCloudConnectionService  cloudConnectionService;
    private final TableContextFactory           tableContextFactory;
    private final ScaleAwareQueryExecutor       queryExecutor;
    private final JdbcTemplate                 jdbcTemplate;
    private final ObjectMapper                 objectMapper;

    public SignalDetectionService(
            SignalRepository             signalRepo,
            KpiDetectorService           kpiDetector,
            TenantCloudConnectionService cloudConnectionService,
            TableContextFactory          tableContextFactory,
            ScaleAwareQueryExecutor      queryExecutor,
            JdbcTemplate                jdbcTemplate,
            ObjectMapper                objectMapper
    ) {
        this.signalRepo               = signalRepo;
        this.kpiDetector              = kpiDetector;
        this.cloudConnectionService   = cloudConnectionService;
        this.tableContextFactory      = tableContextFactory;
        this.queryExecutor            = queryExecutor;
        this.jdbcTemplate             = jdbcTemplate;
        this.objectMapper             = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Run signal detection for all tables in a tenant's catalogue snapshot.
     *
     * @return list of new HIGH/MEDIUM signals that should trigger agents.
     *         LOW signals (baseline-only) are stored but not returned.
     */
    public List<SignalEntity> detectSignals(String clientId, JsonNode catalogueNode) {
        List<SignalEntity> triggered = new ArrayList<>();

        // Resolve cloud connector
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

        AnalysisRunContext signalBudget = AnalysisRunContext.unlimited();

        for (JsonNode tableNode : catalogueNode.path("tables")) {
            KpiDetectorService.ColumnHints hints = kpiDetector.classifyColumns(tableNode);
            if (hints.tableName().isBlank()) continue;

            try {
                TableContext ctx = tableContextFactory.forTableNode(
                        clientId, tableNode, provider, useBQ, useSF, bqCfg, sfCfg,
                        jdbcTemplate, signalBudget);

                for (String col : ctx.hints().numericCols()) {
                    SignalEntity sig = checkMetricShift(clientId, ctx, col);
                    if (sig != null) triggered.add(sig);
                }

                for (String col : limit(ctx.hints().stringCols(), 3)) {
                    SignalEntity sig = checkDistributionChange(clientId, ctx, col);
                    if (sig != null) triggered.add(sig);
                }

                if (ctx.hints().dateCol() != null) {
                    SignalEntity sig = checkTimeTrend(clientId, ctx);
                    if (sig != null) triggered.add(sig);
                }

            } catch (Exception e) {
                System.out.printf("[Signal] Table %s failed: %s%n",
                        hints.tableName(), e.getMessage());
            }
        }

        return triggered;
    }

    // ── Signal checks ─────────────────────────────────────────────────────────

    /** Has the average of a numeric column shifted significantly from its baseline? */
    private SignalEntity checkMetricShift(String clientId, TableContext ctx, String column) {
        KpiDetectorService.ColumnHints hints = ctx.hints();
        if (isDuplicate(clientId, hints.tableName(), "METRIC_SHIFT", column)) return null;

        String colRef = AgentSqlHelper.qualify(column, ctx.provider());
        String sql    = String.format("SELECT AVG(%s) AS val FROM %s",
                colRef, AgentSqlHelper.qualifiedTableRef(ctx) + AgentSqlHelper.windowClause(ctx));

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "Signal metric: " + column, ctx);
        if (rows == null || rows.isEmpty()) return null;

        Double current = toDouble(rows.get(0).get("val"));
        if (current == null) return null;

        return evaluateAndStore(clientId, hints, "METRIC_SHIFT", column, current, rows);
    }

    /**
     * Has the top-value share of a categorical column shifted?
     * e.g. "Electronics" used to be 40% of orders — now it's 60%.
     */
    private SignalEntity checkDistributionChange(String clientId, TableContext ctx, String column) {
        KpiDetectorService.ColumnHints hints = ctx.hints();
        if (isDuplicate(clientId, hints.tableName(), "DISTRIBUTION_CHANGE", column)) return null;

        String colRef = AgentSqlHelper.qualify(column, ctx.provider());
        String sql    = String.format(
                "SELECT %s AS cat, COUNT(*) AS cnt FROM %s " +
                "WHERE %s IS NOT NULL%s GROUP BY %s ORDER BY cnt DESC LIMIT 10",
                colRef, ctx.tableRef(), colRef, AgentSqlHelper.windowAndClause(ctx), colRef);

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "Signal distribution: " + column, ctx);
        if (rows == null || rows.size() < 2) return null;

        double total    = rows.stream().mapToDouble(r -> toDoubleOrZero(r.get("cnt"))).sum();
        if (total == 0) return null;
        double topShare = toDoubleOrZero(rows.get(0).get("cnt")) / total * 100.0;

        return evaluateAndStore(clientId, hints, "DISTRIBUTION_CHANGE", column, topShare, rows);
    }

    /** Has the per-month record volume changed vs the previous period? */
    private SignalEntity checkTimeTrend(String clientId, TableContext ctx) {
        KpiDetectorService.ColumnHints hints = ctx.hints();
        if (isDuplicate(clientId, hints.tableName(), "TIME_TREND", null)) return null;

        String dateRef = AgentSqlHelper.qualify(hints.dateCol(), ctx.provider());
        String truncExpr = AgentSqlHelper.dateTruncMonth(dateRef, ctx.provider());

        String sql = String.format(
                "SELECT %s AS month, COUNT(*) AS records FROM %s " +
                "WHERE %s IS NOT NULL%s GROUP BY 1 ORDER BY 1 DESC LIMIT 3",
                truncExpr, ctx.tableRef(), dateRef, AgentSqlHelper.windowAndClause(ctx));

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "Signal time trend", ctx);
        if (rows == null || rows.size() < 2) return null;

        double latest   = toDoubleOrZero(rows.get(0).get("records"));
        double previous = toDoubleOrZero(rows.get(1).get("records"));
        if (previous == 0) return null;

        // For TIME_TREND use latest vs previous period directly (not the stored baseline)
        double deltaPct    = deltaPct(latest, previous);
        String significance = classify(deltaPct);
        if ("NONE".equals(significance)) return null;

        SignalEntity sig = buildSignal(clientId, hints, "TIME_TREND", null,
                latest, previous, deltaPct, significance, rows);
        return signalRepo.save(sig);
    }

    // ── Baseline comparison logic ─────────────────────────────────────────────

    /**
     * Compares current value against stored baseline.
     * First run: stores as LOW baseline only (returns null — no trigger).
     * Subsequent runs: fires signal if delta is MEDIUM or HIGH.
     */
    private SignalEntity evaluateAndStore(
            String clientId, KpiDetectorService.ColumnHints hints,
            String signalType, String column,
            double current, List<Map<String, Object>> rows
    ) {
        List<SignalEntity> last = signalRepo.findLatestSignal(
                clientId, hints.tableName(), signalType, column);

        if (last.isEmpty()) {
            // First run — store as baseline, don't trigger agents yet
            SignalEntity baseline = buildSignal(clientId, hints, signalType, column,
                    current, current, 0.0, "LOW", rows);
            signalRepo.save(baseline);
            return null;
        }

        double storedBaseline = last.get(0).getValue();
        double deltaPct       = deltaPct(current, storedBaseline);
        String significance   = classify(deltaPct);

        if ("NONE".equals(significance)) return null;

        SignalEntity sig = buildSignal(clientId, hints, signalType, column,
                current, storedBaseline, deltaPct, significance, rows);
        return signalRepo.save(sig);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isDuplicate(String clientId, String tableName,
                                String signalType, String column) {
        LocalDateTime since = LocalDateTime.now().minusHours(DEDUP_HOURS);
        return signalRepo.countRecentDuplicates(
                clientId, tableName, signalType, column, since) > 0;
    }

    private SignalEntity buildSignal(
            String clientId, KpiDetectorService.ColumnHints hints,
            String signalType, String column,
            double value, double baseline, double deltaPct,
            String significance, List<Map<String, Object>> rows
    ) {
        SignalEntity s = new SignalEntity();
        s.setClientId(clientId);
        s.setTableName(hints.tableName());
        s.setTableSchema(hints.tableSchema());
        s.setSignalType(signalType);
        s.setColumnName(column);
        s.setValue(value);
        s.setBaseline(baseline);
        s.setDeltaPct(deltaPct);
        s.setSignificance(significance);
        s.setDetectedAt(LocalDateTime.now());
        s.setRawPayload(toJson(rows.subList(0, Math.min(rows.size(), 10))));
        return s;
    }

    private double deltaPct(double current, double baseline) {
        if (baseline == 0) return 0;
        return ((current - baseline) / Math.abs(baseline)) * 100.0;
    }

    private String classify(double deltaPct) {
        double abs = Math.abs(deltaPct);
        if (abs >= HIGH_THRESHOLD)   return "HIGH";
        if (abs >= MEDIUM_THRESHOLD) return "MEDIUM";
        return "NONE";
    }

    private Double toDouble(Object val) {
        if (val == null) return null;
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private double toDoubleOrZero(Object val) {
        Double d = toDouble(val);
        return d == null ? 0.0 : d;
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
