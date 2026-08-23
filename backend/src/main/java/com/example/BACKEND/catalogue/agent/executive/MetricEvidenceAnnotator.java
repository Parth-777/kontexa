package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Attaches catalogue metric semantics and grand-total contribution shares
 * to warehouse probe rows (Insights-only).
 */
public final class MetricEvidenceAnnotator {

    public static final String SHARE_OF_TOTAL = "share_of_total_pct";
    public static final String GRAND_TOTAL = "grand_total";
    public static final String SEGMENT_COUNT = "segment_count";
    public static final String DISPLAY_UNIT = "metric_display_unit";
    public static final String SCALE = "metric_scale_to_canonical";
    public static final String GRAIN = "metric_grain_label";
    public static final String METRIC_NAME = "metric_column";

    private MetricEvidenceAnnotator() {}

    public static MetricSemantics semantics(TableContext ctx, String metric) {
        if (ctx == null || ctx.enriched() == null || metric == null) {
            return MetricSemantics.unknown();
        }
        EnrichedColInfo info = ctx.enriched().get(metric.toLowerCase(Locale.ROOT));
        if (info == null) {
            for (Map.Entry<String, EnrichedColInfo> e : ctx.enriched().entrySet()) {
                if (e.getKey().equalsIgnoreCase(metric)) {
                    info = e.getValue();
                    break;
                }
            }
        }
        return MetricDisplayFormatter.semanticsOf(info);
    }

    public static double fetchGrandTotal(
            ScaleAwareQueryExecutor executor,
            TableContext ctx,
            String metric,
            String label) {
        String revRef = AgentSqlHelper.qualify(metric, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, revRef + " IS NOT NULL");
        String sql = String.format("SELECT SUM(%s) AS grand_total FROM %s", revRef, from);
        List<Map<String, Object>> rows = executor.execute(sql, label, ctx);
        if (rows == null || rows.isEmpty()) return 0;
        return toDouble(rows.get(0).get("grand_total"));
    }

    public static void annotateSegmentRows(
            List<Map<String, Object>> rows,
            String valueKey,
            double grandTotal,
            MetricSemantics sem,
            String metricColumn) {
        if (rows == null) return;
        int n = rows.size();
        for (Map<String, Object> r : rows) {
            double seg = toDouble(r.get(valueKey));
            double share = grandTotal > 0 ? (seg / grandTotal) * 100.0 : 0;
            r.put(SHARE_OF_TOTAL, Math.round(share * 10.0) / 10.0);
            r.put(GRAND_TOTAL, grandTotal);
            r.put(SEGMENT_COUNT, n);
            putSemantics(r, sem, metricColumn);
        }
    }

    public static void putSemantics(Map<String, Object> row, MetricSemantics sem, String metricColumn) {
        if (row == null) return;
        if (metricColumn != null) row.put(METRIC_NAME, metricColumn);
        if (sem == null) sem = MetricSemantics.unknown();
        if (sem.displayUnit() != null) row.put(DISPLAY_UNIT, sem.displayUnit());
        if (sem.scaleToCanonical() != null) row.put(SCALE, sem.scaleToCanonical());
        if (sem.grainLabel() != null) row.put(GRAIN, sem.grainLabel());
    }

    public static MetricSemantics fromRow(Map<String, Object> row) {
        if (row == null) return MetricSemantics.unknown();
        Double scale = null;
        Object s = row.get(SCALE);
        if (s instanceof Number n) scale = n.doubleValue();
        else if (s != null) {
            try { scale = Double.parseDouble(s.toString()); } catch (Exception ignored) { /* */ }
        }
        return MetricSemantics.of(
                str(row.get(DISPLAY_UNIT)),
                scale,
                str(row.get(GRAIN)));
    }

    public static boolean hasGrandTotalShare(Map<String, Object> row) {
        return row != null && row.containsKey(SHARE_OF_TOTAL) && row.containsKey(GRAND_TOTAL);
    }

    /**
     * Concentration/dominance is allowed when share is against grand total AND
     * either ≥2 segments are present in the evidence set.
     * A single returned segment with 100% share is not sufficient (TOP_N tautology risk).
     */
    public static boolean concentrationEvidenceOk(Map<String, Object> row, int segmentRows) {
        if (!hasGrandTotalShare(row)) return false;
        double share = toDouble(row.get(SHARE_OF_TOTAL));
        if (share < 35) return false;
        int count = segmentRows;
        if (row.containsKey(SEGMENT_COUNT)) {
            count = (int) toDouble(row.get(SEGMENT_COUNT));
        }
        // Require comparative evidence across ≥2 segments
        return count >= 2;
    }

    public static Map<String, Object> metaRow(String metric, String dim, MetricSemantics sem) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("metric", metric);
        meta.put("dimension", dim);
        putSemantics(meta, sem, metric);
        return meta;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }
}
