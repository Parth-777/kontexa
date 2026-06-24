package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.executive.ColumnDiscoveryPlanner;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executive discovery agent — scans the full table schema and runs bounded probes
 * for CFO-style patterns: revenue corridors, zone performance, tip behavior,
 * concentration, and metric×segment rankings. Not limited to one categorical column.
 */
@Service
public class GeneralDiscoveryAgent {

    private static final int TOP_N = 8;

    private final ScaleAwareQueryExecutor queryExecutor;

    public GeneralDiscoveryAgent(ScaleAwareQueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    public List<CollectedData> collect(
            TableContext ctx,
            KpiDetectorService.ColumnHints rawHints,
            int maxProbes
    ) {
        List<CollectedData> out = new ArrayList<>();
        var plan = ColumnDiscoveryPlanner.plan(
                rawHints, ctx.enriched(),
                4, Math.max(6, maxProbes / 2));

        int probes = 0;

        if (plan.routePair().isPresent() && !plan.revenueMetrics().isEmpty()) {
            probes += probeRouteRevenue(ctx, plan.routePair().get(),
                    plan.revenueMetrics().get(0), out);
        }

        for (String metric : plan.revenueMetrics()) {
            if (probes >= maxProbes) break;
            for (String dim : plan.segmentDimensions()) {
                if (probes >= maxProbes) break;
                probes += probeSegmentRanking(ctx, metric, dim, "SUM", out);
                probes += probeConcentration(ctx, metric, dim, out);
            }
        }

        for (String metric : plan.behaviorMetrics()) {
            if (probes >= maxProbes) break;
            for (String dim : plan.segmentDimensions()) {
                if (probes >= maxProbes) break;
                if (dim.toLowerCase().contains("location") || dim.toLowerCase().contains("zone")
                        || dim.toLowerCase().contains("pickup") || dim.toLowerCase().contains("borough")) {
                    probes += probeSegmentRanking(ctx, metric, dim, "AVG", out);
                }
            }
        }

        if (probes < maxProbes && plan.revenueMetrics().size() >= 1) {
            String revenue = plan.revenueMetrics().get(0);
            String distance = plan.behaviorMetrics().stream()
                    .filter(m -> m.toLowerCase().contains("distance"))
                    .findFirst().orElse(null);
            if (distance != null) {
                probes += probeDistanceEfficiency(ctx, revenue, distance, out);
            }
        }

        System.out.printf("[GeneralDiscovery] %s — %d probes, %d datasets%n",
                ctx.hints().tableName(), probes, out.size());
        return out;
    }

    private int probeRouteRevenue(TableContext ctx, ColumnDiscoveryPlanner.RoutePair route,
                                   String revenueMetric, List<CollectedData> out) {
        String puRef = AgentSqlHelper.qualify(route.pickupColumn(), ctx.provider());
        String doRef = AgentSqlHelper.qualify(route.dropoffColumn(), ctx.provider());
        String revRef = AgentSqlHelper.qualify(revenueMetric, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx,
                puRef + " IS NOT NULL AND " + doRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS pickup, %s AS dropoff, SUM(%s) AS route_revenue, COUNT(*) AS trips "
                        + "FROM %s GROUP BY 1, 2 ORDER BY route_revenue DESC LIMIT %d",
                puRef, doRef, revRef, from, TOP_N);

        String label = "DISCOVERY: top revenue corridors by " + revenueMetric;
        return run(ctx, label, sql, out);
    }

    private int probeSegmentRanking(TableContext ctx, String metric, String dim,
                                     String defaultAgg, List<CollectedData> out) {
        String dimRef = AgentSqlHelper.qualify(dim, ctx.provider());
        String metricRef = AgentSqlHelper.qualify(metric, ctx.provider());
        String agg = defaultAgg;
        var info = ctx.enriched().get(metric.toLowerCase());
        if (info != null && List.of("SUM", "AVG", "COUNT").contains(info.aggregationMethod())) {
            agg = info.aggregationMethod();
        }
        String aggExpr = switch (agg) {
            case "AVG" -> "AVG(" + metricRef + ")";
            case "COUNT" -> "COUNT(" + metricRef + ")";
            default -> "SUM(" + metricRef + ")";
        };

        String from = AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL");
        String sql = String.format(
                "SELECT %s AS segment, %s AS metric_value, COUNT(*) AS row_count "
                        + "FROM %s GROUP BY 1 ORDER BY metric_value DESC LIMIT %d",
                dimRef, aggExpr, from, TOP_N);

        String label = "DISCOVERY: " + metric + " by " + dim;
        return run(ctx, label, sql, out);
    }

    private int probeConcentration(TableContext ctx, String metric, String dim,
                                    List<CollectedData> out) {
        String dimRef = AgentSqlHelper.qualify(dim, ctx.provider());
        String metricRef = AgentSqlHelper.qualify(metric, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS segment, SUM(%s) AS segment_total FROM %s "
                        + "GROUP BY 1 ORDER BY segment_total DESC LIMIT %d",
                dimRef, metricRef, from, TOP_N);

        String label = "DISCOVERY: " + metric + " concentration by " + dim;
        List<Map<String, Object>> rows = queryExecutor.execute(sql, label, ctx);
        if (rows.isEmpty()) return 1;

        double total = rows.stream().mapToDouble(r -> toDouble(r.get("segment_total"))).sum();
        if (total <= 0) return 1;

        double topShare = toDouble(rows.get(0).get("segment_total")) / total * 100.0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metric", metric);
        summary.put("dimension", dim);
        summary.put("top_segment", rows.get(0).get("segment"));
        summary.put("top_share_pct", Math.round(topShare * 10.0) / 10.0);
        summary.put("top_value", rows.get(0).get("segment_total"));
        summary.put("total", total);

        out.add(new CollectedData(
                "DISCOVERY: " + metric + " concentration " + dim,
                sql, List.of(summary)));
        return 1;
    }

    private int probeDistanceEfficiency(TableContext ctx, String revenue, String distance,
                                         List<CollectedData> out) {
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String distRef = AgentSqlHelper.qualify(distance, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx,
                revRef + " IS NOT NULL AND " + distRef + " > 0");

        String sql = String.format(
                "SELECT CASE "
                        + "WHEN %s <= 1 THEN '0-1 mi' "
                        + "WHEN %s <= 3 THEN '1-3 mi' "
                        + "WHEN %s <= 10 THEN '3-10 mi' "
                        + "ELSE '10+ mi' END AS distance_band, "
                        + "AVG(%s) AS avg_revenue, SUM(%s) AS total_revenue, COUNT(*) AS trips "
                        + "FROM %s GROUP BY 1 ORDER BY avg_revenue DESC",
                distRef, distRef, distRef, revRef, revRef, from);

        String label = "DISCOVERY: revenue efficiency by " + distance + " band";
        return run(ctx, label, sql, out);
    }

    private int run(TableContext ctx, String label, String sql, List<CollectedData> out) {
        List<Map<String, Object>> rows = queryExecutor.execute(sql, label, ctx);
        if (!rows.isEmpty()) {
            out.add(new CollectedData(label, sql, rows));
        }
        return 1;
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
