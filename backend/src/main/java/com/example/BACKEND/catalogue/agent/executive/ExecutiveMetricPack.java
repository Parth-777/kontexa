package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-computes decision-grade metrics (MoM delta, contribution, concentration) per table.
 * Outputs labeled EXEC:* CollectedData for lens agents and synthesis.
 */
@Component
public class ExecutiveMetricPack {

    private static final int MAX_METRICS = 4;
    private static final int MAX_DIMS    = 4;
    private static final int TOP_DIMS    = 3;

    private final ScaleAwareQueryExecutor queryExecutor;

    public ExecutiveMetricPack(ScaleAwareQueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    public List<CollectedData> collect(TableContext ctx) {
        List<CollectedData> out = new ArrayList<>();
        String tableName = ctx.hints().tableName();
        List<String> metrics = ctx.hints().numericCols();
        if (metrics.size() > MAX_METRICS) {
            metrics = metrics.subList(0, MAX_METRICS);
        }

        for (String metric : metrics) {
            EnrichedColInfo info = ctx.enriched().get(metric.toLowerCase());
            collectPeriodDelta(ctx, tableName, metric, info, out);
            collectContribution(ctx, tableName, metric, info, out);
        }
        return out;
    }

    private void collectPeriodDelta(TableContext ctx, String tableName, String metric,
                                     EnrichedColInfo info, List<CollectedData> out) {
        if (ctx.hints().dateCol() == null) return;

        String provider  = ctx.provider();
        String dateRef   = AgentSqlHelper.qualify(ctx.hints().dateCol(), provider);
        String metricRef = AgentSqlHelper.qualify(metric, provider);
        String window    = AgentSqlHelper.windowClause(ctx);

        String agg = (info != null && List.of("SUM", "COUNT", "AVG").contains(info.aggregationMethod()))
                ? info.aggregationMethod() : "SUM";
        String aggExpr = switch (agg) {
            case "COUNT" -> "COUNT(" + metricRef + ")";
            case "AVG"   -> "AVG(" + metricRef + ")";
            default      -> "SUM(" + metricRef + ")";
        };

        String periodExpr = AgentSqlHelper.dateTruncMonth(dateRef, provider);

        String sql = String.format(
                "SELECT %s AS period, %s AS metric_value FROM %s " +
                "GROUP BY period ORDER BY period DESC LIMIT 2",
                periodExpr, aggExpr, AgentSqlHelper.qualifiedTableRef(ctx) + window);

        String label = "EXEC: " + tableName + " " + metric + " MoM delta";
        List<Map<String, Object>> rows = queryExecutor.execute(sql, label, ctx);
        if (rows.size() >= 2) {
            double current = toDouble(rows.get(0).get("metric_value"));
            double prior   = toDouble(rows.get(1).get("metric_value"));
            double deltaPct = prior == 0 ? 0 : ((current - prior) / Math.abs(prior)) * 100.0;

            List<Map<String, Object>> summary = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", metric);
            row.put("current_period", rows.get(0).get("period"));
            row.put("current_value", current);
            row.put("prior_period", rows.get(1).get("period"));
            row.put("prior_value", prior);
            row.put("delta_pct", Math.round(deltaPct * 10.0) / 10.0);
            row.put("direction", deltaPct > 0.5 ? "UP" : deltaPct < -0.5 ? "DOWN" : "FLAT");
            summary.add(row);
            out.add(new CollectedData(label, sql, summary));
        } else if (!rows.isEmpty()) {
            out.add(new CollectedData(label, sql, rows));
        }
    }

    private void collectContribution(TableContext ctx, String tableName, String metric,
                                      EnrichedColInfo info, List<CollectedData> out) {
        List<String> dims = ctx.hints().stringCols();
        if (dims.isEmpty()) return;
        if (dims.size() > MAX_DIMS) dims = dims.subList(0, MAX_DIMS);

        for (String dim : dims) {
            collectContributionForDim(ctx, tableName, metric, dim, info, out);
        }
    }

    private void collectContributionForDim(TableContext ctx, String tableName, String metric,
                                              String dim, EnrichedColInfo info, List<CollectedData> out) {
        String provider  = ctx.provider();
        String dimRef    = AgentSqlHelper.qualify(dim, provider);
        String metricRef = AgentSqlHelper.qualify(metric, provider);
        String window    = AgentSqlHelper.windowClause(ctx);

        String agg = (info != null && List.of("SUM", "COUNT", "AVG").contains(info.aggregationMethod()))
                ? info.aggregationMethod() : "SUM";
        String aggExpr = switch (agg) {
            case "COUNT" -> "COUNT(" + metricRef + ")";
            case "AVG"   -> "AVG(" + metricRef + ")";
            default      -> "SUM(" + metricRef + ")";
        };

        String from = AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL");
        String sql = String.format(
                "SELECT %s AS segment, %s AS segment_total FROM %s " +
                "GROUP BY %s ORDER BY segment_total DESC LIMIT %d",
                dimRef, aggExpr, from, dimRef, TOP_DIMS);

        String label = "EXEC: " + tableName + " " + metric + " contribution by " + dim;
        List<Map<String, Object>> rows = queryExecutor.execute(sql, label, ctx);
        if (rows.isEmpty()) return;

        double total = rows.stream().mapToDouble(r -> toDouble(r.get("segment_total"))).sum();
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            double segTotal = toDouble(r.get("segment_total"));
            row.put("share_pct", total > 0 ? Math.round((segTotal / total) * 1000.0) / 10.0 : 0);
            enriched.add(row);
        }
        out.add(new CollectedData(label, sql, enriched));

        if (!enriched.isEmpty() && total > 0) {
            double topShare = toDouble(enriched.get(0).get("share_pct"));
            Map<String, Object> pareto = new LinkedHashMap<>();
            pareto.put("metric", metric);
            pareto.put("dimension", dim);
            pareto.put("top_segment", enriched.get(0).get("segment"));
            pareto.put("top_share_pct", topShare);
            pareto.put("concentration_note",
                    topShare >= 50 ? "High concentration — top segment dominates" : "Moderate concentration");
            out.add(new CollectedData(
                    "EXEC: " + tableName + " " + metric + " concentration",
                    "-- derived from contribution",
                    List.of(pareto)));
        }
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
