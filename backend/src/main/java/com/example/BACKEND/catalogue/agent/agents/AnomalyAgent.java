package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects statistical anomalies in metric columns using Z-score analysis.
 *
 * Detection strategy (pure Java, no LLM):
 *   1. Per metric column, run one SQL call:
 *        SELECT AVG, STDDEV, MIN, MAX, COUNT
 *   2. Compute Z-scores for the extreme values:
 *        z_max = (max - mean) / std
 *        z_min = (mean - min) / std
 *   3. Flag as anomaly if either Z-score exceeds THRESHOLD (default 2.5)
 *
 * LLM is only used for the description — it receives the raw numbers and
 * writes a 1-2 sentence business explanation.
 *
 * The agent is intentionally lightweight: one stats query per metric column,
 * one tiny LLM call per detected anomaly. No heavy data transfer.
 */
@Service
public class AnomalyAgent {

    private static final double Z_THRESHOLD = 2.5;
    private static final int    MAX_METRICS = 6;

    private final BigQueryConnectorService  bigQueryConnectorService;
    private final SnowflakeConnectorService snowflakeConnectorService;
    private final OpenAiClient             openAiClient;

    public AnomalyAgent(BigQueryConnectorService  bigQueryConnectorService,
                        SnowflakeConnectorService snowflakeConnectorService,
                        OpenAiClient             openAiClient) {
        this.bigQueryConnectorService  = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.openAiClient             = openAiClient;
    }

    /**
     * Runs anomaly detection on all metric columns of the table.
     * Returns a list of anomaly objects ready to be added to the dashboard result.
     */
    public List<AgentDashboardResult.Anomaly> detectAnomalies(TableContext ctx) {
        List<AgentDashboardResult.Anomaly> anomalies = new ArrayList<>();

        List<String> metrics = limit(ctx.hints().numericCols(), MAX_METRICS);
        if (metrics.isEmpty()) return anomalies;

        for (String metric : metrics) {
            try {
                ColStats stats = queryStats(ctx, metric);
                if (stats == null || stats.count < 10 || stats.std <= 0) continue;

                double zMax = (stats.max - stats.mean) / stats.std;
                double zMin = (stats.mean - stats.min) / stats.std;

                if (Math.max(zMax, zMin) < Z_THRESHOLD) continue;

                // Choose which extreme is anomalous
                boolean isHighAnomaly = zMax >= zMin;
                double  outlierValue  = isHighAnomaly ? stats.max : stats.min;
                double  zScore        = isHighAnomaly ? zMax : zMin;
                double  changePct     = stats.mean > 0
                        ? ((outlierValue - stats.mean) / Math.abs(stats.mean)) * 100.0 : 0;
                String  direction     = isHighAnomaly ? "UP" : "DOWN";

                String description = describeAnomaly(
                        ctx.hints().tableName(), metric,
                        stats, outlierValue, zScore, isHighAnomaly);

                anomalies.add(new AgentDashboardResult.Anomaly(
                        metric, description,
                        Math.round(changePct * 10.0) / 10.0,
                        direction
                ));

                System.out.printf("[AnomalyAgent] %s.%s z=%.2f → %s%n",
                        ctx.hints().tableName(), metric, zScore, direction);

            } catch (Exception e) {
                System.out.printf("[AnomalyAgent] Failed for %s.%s: %s%n",
                        ctx.hints().tableName(), metric, e.getMessage());
            }
        }

        return anomalies;
    }

    // ── Stats query ───────────────────────────────────────────────────────────

    private ColStats queryStats(TableContext ctx, String metric) {
        boolean isBQ      = "bigquery".equalsIgnoreCase(ctx.provider());
        boolean isSF      = "snowflake".equalsIgnoreCase(ctx.provider());
        String  metricRef = isBQ ? "`" + metric + "`" : metric;

        String stdExpr;
        if (isBQ)      stdExpr = "STDDEV(" + metricRef + ")";
        else if (isSF) stdExpr = "STDDEV(" + metricRef + ")";
        else           stdExpr = "STDDEV(" + metricRef + ")";  // same for PostgreSQL

        String sql = String.format(
                "SELECT AVG(%s) AS mean, %s AS std, " +
                "MIN(%s) AS min_val, MAX(%s) AS max_val, COUNT(%s) AS cnt " +
                "FROM %s WHERE %s IS NOT NULL",
                metricRef, stdExpr,
                metricRef, metricRef, metricRef,
                ctx.tableRef(), metricRef
        );

        List<Map<String, Object>> rows = execute(sql, ctx);
        if (rows == null || rows.isEmpty()) return null;

        Map<String, Object> row = rows.get(0);
        return new ColStats(
                toDouble(row.get("mean")),
                toDouble(row.get("std")),
                toDouble(row.get("min_val")),
                toDouble(row.get("max_val")),
                toLong(row.get("cnt"))
        );
    }

    // ── LLM description ───────────────────────────────────────────────────────

    /**
     * Asks the LLM to write a short, plain-English business description
     * of the detected anomaly. Falls back to a template if the LLM fails.
     */
    private String describeAnomaly(String table, String metric, ColStats stats,
                                    double outlierValue, double zScore, boolean isHigh) {
        String prompt = String.format(
                "In the '%s' table, the metric '%s' has an anomaly.\n" +
                "Statistics: mean=%.2f, std=%.2f, min=%.2f, max=%.2f, count=%d.\n" +
                "The %s extreme value is %.2f, which is %.1f standard deviations from the mean.\n" +
                "Write ONE sentence describing this anomaly and what it might mean for the business. " +
                "Be specific and business-focused. Do not say 'standard deviation'.",
                table, metric, stats.mean, stats.std, stats.min, stats.max, stats.count,
                isHigh ? "upper" : "lower", outlierValue, zScore
        );

        try {
            return openAiClient.chat(
                    "You are a data analyst. Respond with exactly one clear, business-focused sentence.",
                    prompt
            ).trim();
        } catch (Exception e) {
            return String.format(
                    "Unusual %s detected in %s: %.2f is %.1f standard deviations from the average of %.2f.",
                    isHigh ? "spike" : "drop", metric, outlierValue, zScore, stats.mean
            );
        }
    }

    // ── Query execution ───────────────────────────────────────────────────────

    private List<Map<String, Object>> execute(String sql, TableContext ctx) {
        try {
            if (ctx.useBQ() && ctx.bqCfg().isPresent()) {
                var c = ctx.bqCfg().get();
                return bigQueryConnectorService.executeSelect(
                        c.projectId(), c.serviceAccountJson(), c.location(), c.dataset(), sql);
            } else if (ctx.useSF() && ctx.sfCfg().isPresent()) {
                var c = ctx.sfCfg().get();
                return snowflakeConnectorService.executeSelect(
                        c.account(), c.warehouse(), c.database(),
                        c.schema(), c.username(), c.password(), sql);
            } else {
                return ctx.jdbcTemplate().queryForList(sql);
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record ColStats(double mean, double std, double min, double max, long count) {}

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0;
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
