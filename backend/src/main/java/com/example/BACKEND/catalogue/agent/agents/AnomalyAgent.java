package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.executive.ExecutiveVoice;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import com.example.BACKEND.catalogue.agent.scale.ScaleTier;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AnomalyAgent {

    private static final double Z_THRESHOLD = 2.5;

    private final ScaleAwareQueryExecutor queryExecutor;
    private final OpenAiClient openAiClient;

    public AnomalyAgent(ScaleAwareQueryExecutor queryExecutor, OpenAiClient openAiClient) {
        this.queryExecutor = queryExecutor;
        this.openAiClient = openAiClient;
    }

    public List<AgentDashboardResult.Anomaly> detectAnomalies(TableContext ctx) {
        List<AgentDashboardResult.Anomaly> anomalies = new ArrayList<>();
        List<String> metrics = ctx.hints().numericCols();
        if (metrics.isEmpty()) return anomalies;

        boolean bucketed = (ctx.tier() == ScaleTier.LARGE || ctx.tier() == ScaleTier.MEDIUM)
                && ctx.hints().dateCol() != null;

        for (String metric : metrics) {
            try {
                if (bucketed) {
                    detectFromMonthlyBuckets(ctx, metric, anomalies);
                } else {
                    detectFromGlobalStats(ctx, metric, anomalies);
                }
            } catch (Exception e) {
                System.out.printf("[AnomalyAgent] Failed for %s.%s: %s%n",
                        ctx.hints().tableName(), metric, e.getMessage());
            }
        }

        return anomalies;
    }

    private void detectFromMonthlyBuckets(TableContext ctx, String metric,
                                           List<AgentDashboardResult.Anomaly> anomalies) {
        String dateCol = ctx.hints().dateCol();
        String provider = ctx.provider();
        String dateRef = AgentSqlHelper.qualify(dateCol, provider);
        String metricRef = AgentSqlHelper.qualify(metric, provider);
        EnrichedColInfo metricInfo = ctx.enriched().get(metric.toLowerCase());
        String agg = (metricInfo != null && List.of("SUM", "COUNT", "AVG").contains(metricInfo.aggregationMethod()))
                ? metricInfo.aggregationMethod() : "SUM";
        String aggExpression = switch (agg) {
            case "COUNT" -> "COUNT(" + metricRef + ")";
            case "AVG" -> "AVG(" + metricRef + ")";
            default -> "SUM(" + metricRef + ")";
        };

        String trunc = dateTruncMonthly(dateRef, provider);
        String sql = String.format(
                "SELECT %s AS period, %s AS metric_value FROM %s " +
                "GROUP BY period ORDER BY period DESC LIMIT 24",
                trunc, aggExpression, AgentSqlHelper.qualifiedTableRef(ctx) + AgentSqlHelper.windowClause(ctx));

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "Anomaly monthly: " + metric, ctx);
        if (rows == null || rows.size() < 4) return;

        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get("metric_value");
            if (v != null) values.add(toDouble(v));
        }
        if (values.size() < 4) return;

        double mean = values.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = values.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
        double std = Math.sqrt(variance);
        if (std <= 0) return;

        double latest = values.get(0);
        double z = Math.abs((latest - mean) / std);
        if (z < Z_THRESHOLD) return;

        boolean isHigh = latest > mean;
        double changePct = mean != 0 ? ((latest - mean) / Math.abs(mean)) * 100.0 : 0;

        String description = ctx.tier() == ScaleTier.LARGE
                ? templateDescription(metric, latest, mean, isHigh)
                : describeAnomaly(ctx.hints().tableName(), metric,
                new ColStats(mean, std, values.stream().mapToDouble(d -> d).min().orElse(0),
                        values.stream().mapToDouble(d -> d).max().orElse(0), values.size()),
                latest, z, isHigh);

        anomalies.add(new AgentDashboardResult.Anomaly(
                metric, description, Math.round(changePct * 10.0) / 10.0, isHigh ? "UP" : "DOWN"));
    }

    private void detectFromGlobalStats(TableContext ctx, String metric,
                                        List<AgentDashboardResult.Anomaly> anomalies) {
        ColStats stats = queryStats(ctx, metric);
        if (stats == null || stats.count < 10 || stats.std <= 0) return;

        double zMax = (stats.max - stats.mean) / stats.std;
        double zMin = (stats.mean - stats.min) / stats.std;
        if (Math.max(zMax, zMin) < Z_THRESHOLD) return;

        boolean isHighAnomaly = zMax >= zMin;
        double outlierValue = isHighAnomaly ? stats.max : stats.min;
        double zScore = isHighAnomaly ? zMax : zMin;
        double changePct = stats.mean > 0
                ? ((outlierValue - stats.mean) / Math.abs(stats.mean)) * 100.0 : 0;

        String description = describeAnomaly(ctx.hints().tableName(), metric,
                stats, outlierValue, zScore, isHighAnomaly);

        anomalies.add(new AgentDashboardResult.Anomaly(
                metric, description, Math.round(changePct * 10.0) / 10.0,
                isHighAnomaly ? "UP" : "DOWN"));
    }

    private ColStats queryStats(TableContext ctx, String metric) {
        String metricRef = AgentSqlHelper.qualify(metric, ctx.provider());
        String stdExpr = "STDDEV(" + metricRef + ")";
        String window = AgentSqlHelper.windowClause(ctx);

        String sql = String.format(
                "SELECT AVG(%s) AS mean, %s AS std, MIN(%s) AS min_val, MAX(%s) AS max_val, COUNT(%s) AS cnt " +
                "FROM %s",
                metricRef, stdExpr, metricRef, metricRef, metricRef,
                AgentSqlHelper.fromWithPredicates(ctx, metricRef + " IS NOT NULL"));

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "Anomaly stats: " + metric, ctx);
        if (rows == null || rows.isEmpty()) return null;

        Map<String, Object> row = rows.get(0);
        return new ColStats(toDouble(row.get("mean")), toDouble(row.get("std")),
                toDouble(row.get("min_val")), toDouble(row.get("max_val")), toLong(row.get("cnt")));
    }

    private String dateTruncMonthly(String dateRef, String provider) {
        return AgentSqlHelper.dateTruncMonth(dateRef, provider);
    }

    private String templateDescription(String metric, double latest, double mean, boolean isHigh) {
        double pct = mean != 0 ? Math.abs((latest - mean) / mean) * 100.0 : 0;
        String label = ExecutiveVoice.humanizeMetric(metric);
        return String.format("%s %s %s (%s vs monthly run-rate %s).",
                label,
                isHigh ? "spiked to" : "dropped to",
                ExecutiveVoice.formatValue(latest),
                ExecutiveVoice.formatPercent(pct),
                ExecutiveVoice.formatValue(mean));
    }

    private String describeAnomaly(String table, String metric, ColStats stats,
                                    double outlierValue, double zScore, boolean isHigh) {
        try {
            String prompt = String.format(
                    "Table '%s', metric '%s': mean=%.2f, min=%.2f, max=%.2f. %s extreme=%.2f (z=%.1f). One business sentence.",
                    table, metric, stats.mean, stats.min, stats.max,
                    isHigh ? "Upper" : "Lower", outlierValue, zScore);
            return openAiClient.chat(
                    ExecutiveVoice.PERSONA + " One sentence only — business impact, no column names.",
                    prompt).trim();
        } catch (Exception e) {
            return templateDescription(metric, outlierValue, stats.mean, isHigh);
        }
    }

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
}
