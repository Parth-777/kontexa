package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.executive.ColumnDiscoveryPlanner;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Revenue Model agent — dedicated scan of the full table for executive revenue questions:
 * sources, weak areas, performers, trends, and factor contribution. Schema-driven.
 */
@Service
public class RevenueModelAgent {

    private static final int TOP_N = 10;
    private static final int WEAK_N = 5;

    private final ScaleAwareQueryExecutor queryExecutor;

    public RevenueModelAgent(ScaleAwareQueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    public List<CollectedData> collect(
            TableContext ctx,
            KpiDetectorService.ColumnHints rawHints,
            int maxProbes
    ) {
        List<CollectedData> out = new ArrayList<>();
        var plan = ColumnDiscoveryPlanner.plan(rawHints, ctx.enriched(), 3, 8);

        List<String> revenueMetrics = plan.revenueMetrics();
        if (revenueMetrics.isEmpty()) {
            System.out.printf("[RevenueModel] %s — no revenue metrics found%n", ctx.hints().tableName());
            return out;
        }

        String primaryRevenue = revenueMetrics.get(0);
        int probes = 0;

        probes += probeTotalSummary(ctx, primaryRevenue, out);

        if (ctx.hints().dateCol() != null && probes < maxProbes) {
            probes += probeMonthlyTrend(ctx, primaryRevenue, out);
            probes += probeTrendDelta(ctx, primaryRevenue, out);
        }

        if (plan.routePair().isPresent() && probes < maxProbes) {
            probes += probeRouteRevenue(ctx, plan.routePair().get(), primaryRevenue, out);
        }

        for (String dim : plan.segmentDimensions()) {
            if (probes >= maxProbes) break;
            probes += probeRevenueSources(ctx, primaryRevenue, dim, out);
            if (probes >= maxProbes) break;
            probes += probeWeakAreas(ctx, primaryRevenue, dim, out);
            if (probes >= maxProbes) break;
            probes += probeFactorContribution(ctx, primaryRevenue, dim, out);
        }

        for (String secondary : revenueMetrics) {
            if (probes >= maxProbes || secondary.equals(primaryRevenue)) continue;
            probes += probeComponentShare(ctx, primaryRevenue, secondary, out);
        }

        System.out.printf("[RevenueModel] %s — %d probes, %d datasets, primary=%s%n",
                ctx.hints().tableName(), probes, out.size(), primaryRevenue);
        return out;
    }

    /** i) Where did most revenue come from — total in window */
    private int probeTotalSummary(TableContext ctx, String revenue, List<CollectedData> out) {
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, revRef + " IS NOT NULL");
        String sql = String.format(
                "SELECT SUM(%s) AS total_revenue, AVG(%s) AS avg_revenue, COUNT(*) AS trip_count FROM %s",
                revRef, revRef, from);

        return run(ctx, "REVENUE: total summary " + revenue, sql, out);
    }

    /** iv) Revenue trends — monthly series */
    private int probeMonthlyTrend(TableContext ctx, String revenue, List<CollectedData> out) {
        String dateRef = AgentSqlHelper.qualify(ctx.hints().dateCol(), ctx.provider());
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String periodExpr = AgentSqlHelper.dateTruncMonth(dateRef, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, revRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS period, SUM(%s) AS revenue, COUNT(*) AS volume "
                        + "FROM %s GROUP BY 1 ORDER BY 1 DESC LIMIT 12",
                periodExpr, revRef, from);

        return run(ctx, "REVENUE: monthly trend " + revenue, sql, out);
    }

    /** iv) Latest period vs prior — trend direction */
    private int probeTrendDelta(TableContext ctx, String revenue, List<CollectedData> out) {
        String dateRef = AgentSqlHelper.qualify(ctx.hints().dateCol(), ctx.provider());
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String periodExpr = AgentSqlHelper.dateTruncMonth(dateRef, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, revRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS period, SUM(%s) AS revenue FROM %s "
                        + "GROUP BY 1 ORDER BY 1 DESC LIMIT 2",
                periodExpr, revRef, from);

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "REVENUE: trend delta " + revenue, ctx);
        if (rows.size() < 2) return rows.isEmpty() ? 0 : 1;

        double current = toDouble(rows.get(0).get("revenue"));
        double prior = toDouble(rows.get(1).get("revenue"));
        double deltaPct = prior == 0 ? 0 : ((current - prior) / Math.abs(prior)) * 100.0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metric", revenue);
        summary.put("current_period", rows.get(0).get("period"));
        summary.put("current_revenue", current);
        summary.put("prior_period", rows.get(1).get("period"));
        summary.put("prior_revenue", prior);
        summary.put("delta_pct", Math.round(deltaPct * 10.0) / 10.0);
        summary.put("direction", deltaPct > 1 ? "UP" : deltaPct < -1 ? "DOWN" : "FLAT");

        out.add(new CollectedData("REVENUE: period-over-period " + revenue, sql, List.of(summary)));
        return 1;
    }

    /** i) Revenue sources + iii) top performers by segment */
    private int probeRevenueSources(TableContext ctx, String revenue, String dim, List<CollectedData> out) {
        String dimRef = AgentSqlHelper.qualify(dim, ctx.provider());
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL AND " + revRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS segment, SUM(%s) AS segment_revenue, COUNT(*) AS trips, "
                        + "AVG(%s) AS avg_revenue "
                        + "FROM %s GROUP BY 1 ORDER BY segment_revenue DESC LIMIT %d",
                dimRef, revRef, revRef, from, TOP_N);

        return run(ctx, "REVENUE: sources by " + dim + " " + revenue, sql, out);
    }

    /** ii) Weak revenue areas — bottom segments */
    private int probeWeakAreas(TableContext ctx, String revenue, String dim, List<CollectedData> out) {
        String dimRef = AgentSqlHelper.qualify(dim, ctx.provider());
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL AND " + revRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS segment, SUM(%s) AS segment_revenue, COUNT(*) AS trips "
                        + "FROM %s GROUP BY 1 HAVING COUNT(*) >= 10 "
                        + "ORDER BY segment_revenue ASC LIMIT %d",
                dimRef, revRef, from, WEAK_N);

        return run(ctx, "REVENUE: weak areas by " + dim + " " + revenue, sql, out);
    }

    /** v/vi) Factor contribution — share of total revenue by segment */
    private int probeFactorContribution(TableContext ctx, String revenue, String dim, List<CollectedData> out) {
        String dimRef = AgentSqlHelper.qualify(dim, ctx.provider());
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS factor, SUM(%s) AS factor_revenue FROM %s "
                        + "GROUP BY 1 ORDER BY factor_revenue DESC LIMIT %d",
                dimRef, revRef, from, TOP_N);

        List<Map<String, Object>> rows = queryExecutor.execute(
                sql, "REVENUE: factor contribution " + dim, ctx);
        if (rows.isEmpty()) return 1;

        double total = rows.stream().mapToDouble(r -> toDouble(r.get("factor_revenue"))).sum();
        if (total <= 0) return 1;

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            double share = toDouble(r.get("factor_revenue")) / total * 100.0;
            row.put("share_pct", Math.round(share * 10.0) / 10.0);
            enriched.add(row);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("revenue_metric", revenue);
        meta.put("factor_dimension", dim);
        meta.put("strongest_factor", enriched.get(0).get("factor"));
        meta.put("strongest_share_pct", enriched.get(0).get("share_pct"));
        meta.put("weakest_factor", enriched.get(enriched.size() - 1).get("factor"));
        meta.put("weakest_share_pct", enriched.get(enriched.size() - 1).get("share_pct"));
        meta.put("total_revenue", total);

        List<Map<String, Object>> payload = new ArrayList<>();
        payload.add(meta);
        payload.addAll(enriched);

        out.add(new CollectedData(
                "REVENUE: factor model " + revenue + " by " + dim, sql, payload));
        return 1;
    }

    /** vi) Secondary revenue components vs primary (e.g. tips vs fare) */
    private int probeComponentShare(TableContext ctx, String primary, String component, List<CollectedData> out) {
        String pRef = AgentSqlHelper.qualify(primary, ctx.provider());
        String cRef = AgentSqlHelper.qualify(component, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, pRef + " IS NOT NULL AND " + cRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT SUM(%s) AS primary_total, SUM(%s) AS component_total FROM %s",
                pRef, cRef, from);

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "REVENUE: component " + component, ctx);
        if (rows.isEmpty()) return 0;

        double primaryTotal = toDouble(rows.get(0).get("primary_total"));
        double componentTotal = toDouble(rows.get(0).get("component_total"));
        if (primaryTotal <= 0) return 1;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("primary_metric", primary);
        summary.put("component_metric", component);
        summary.put("component_share_pct",
                Math.round((componentTotal / primaryTotal) * 1000.0) / 10.0);
        summary.put("primary_total", primaryTotal);
        summary.put("component_total", componentTotal);

        out.add(new CollectedData(
                "REVENUE: component share " + component + " of " + primary, sql, List.of(summary)));
        return 1;
    }

    private int probeRouteRevenue(TableContext ctx, ColumnDiscoveryPlanner.RoutePair route,
                                   String revenue, List<CollectedData> out) {
        String puRef = AgentSqlHelper.qualify(route.pickupColumn(), ctx.provider());
        String doRef = AgentSqlHelper.qualify(route.dropoffColumn(), ctx.provider());
        String revRef = AgentSqlHelper.qualify(revenue, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx,
                puRef + " IS NOT NULL AND " + doRef + " IS NOT NULL");

        String sql = String.format(
                "SELECT %s AS pickup, %s AS dropoff, SUM(%s) AS route_revenue "
                        + "FROM %s GROUP BY 1, 2 ORDER BY route_revenue DESC LIMIT %d",
                puRef, doRef, revRef, from, TOP_N);

        return run(ctx, "REVENUE: top corridors " + revenue, sql, out);
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
