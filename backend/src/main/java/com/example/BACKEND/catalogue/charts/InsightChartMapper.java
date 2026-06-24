package com.example.BACKEND.catalogue.charts;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps collected datasets to chart specs for insight cards.
 *
 * Phase 1 focuses on REVENUE:* and DISCOVERY:* datasets (bar/line/donut).
 */
@Component
public class InsightChartMapper {

    public ChartSpec chartFor(AgentDashboardResult.InsightCard card, List<CollectedData> collected) {
        if (card == null || collected == null || collected.isEmpty()) return null;

        CollectedData cd = findBestDataset(card, collected);
        if (cd == null || cd.rows() == null || cd.rows().isEmpty()) return null;

        String label = cd.label() == null ? "" : cd.label();
        String lower = label.toLowerCase(Locale.ROOT);

        // Revenue factor model: share_pct by factor
        if (lower.startsWith("revenue: factor model") || (lower.contains("factor model") && lower.startsWith("revenue:"))) {
            return buildFactorModel(cd);
        }

        // Revenue sources / weak areas / discovery ranking: segment vs metric value
        if (lower.contains("sources by") || lower.contains("weak areas by") || lower.startsWith("discovery:") && lower.contains(" by ")) {
            return buildSegmentBar(cd, "segment", guessValueKey(cd, List.of("segment_revenue", "metric_value")));
        }

        // Revenue monthly trend / trends over time
        if (lower.contains("monthly trend") || lower.startsWith("trend:")) {
            return buildLine(cd, "period", guessValueKey(cd, List.of("revenue", "metric_value")));
        }

        // Corridors: pickup→dropoff ranked by revenue
        if (lower.contains("corridor") || lower.contains("corridors") || lower.contains("top corridors")) {
            return buildCorridorBar(cd);
        }

        // Component share: donut
        if (lower.contains("component share")) {
            return buildComponentShare(cd);
        }

        // Fallback: if dataset has segment + value
        if (cd.rows().get(0).containsKey("segment")) {
            return buildSegmentBar(cd, "segment", guessValueKey(cd, List.of("metric_value", "segment_total", "segment_revenue")));
        }

        return null;
    }

    private CollectedData findBestDataset(AgentDashboardResult.InsightCard card, List<CollectedData> collected) {
        String agent = safeLower(card.getAgentName());
        boolean preferRevenue = agent.contains("revenue");
        boolean preferDiscovery = agent.contains("discovery");

        String sourceHint = firstLower(card.getSourceColumns());

        // 1) Strong match by label family + source column
        if (sourceHint != null && !sourceHint.isBlank()) {
            for (CollectedData cd : collected) {
                String lbl = safeLower(cd.label());
                if (preferRevenue && !lbl.startsWith("revenue:")) continue;
                if (preferDiscovery && !lbl.startsWith("discovery:")) continue;
                if (lbl.contains(sourceHint)) return cd;
            }
        }

        // 2) Prefer revenue datasets for revenue cards
        if (preferRevenue) {
            CollectedData best = firstWithPrefix(collected, "revenue: factor model");
            if (best != null) return best;
            best = firstWithPrefix(collected, "revenue: monthly trend");
            if (best != null) return best;
            best = firstWithPrefix(collected, "revenue: sources by");
            if (best != null) return best;
        }

        // 3) Prefer discovery datasets for discovery cards
        if (preferDiscovery) {
            CollectedData best = firstWithPrefix(collected, "discovery:");
            if (best != null) return best;
        }

        // 4) Generic: choose first revenue/discovery dataset
        CollectedData any = firstWithPrefix(collected, "revenue:");
        if (any != null) return any;
        any = firstWithPrefix(collected, "discovery:");
        if (any != null) return any;
        any = firstWithPrefix(collected, "trend:");
        if (any != null) return any;
        return collected.get(0);
    }

    private ChartSpec buildFactorModel(CollectedData cd) {
        // Payload shape: first row meta + remaining rows with factor_revenue/share_pct
        List<Map<String, Object>> rows = cd.rows();
        int start = (rows.get(0).containsKey("strongest_factor") || rows.get(0).containsKey("revenue_metric")) ? 1 : 0;
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = start; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (!r.containsKey("factor") && r.containsKey("segment")) {
                // tolerate alternate naming
                r = new LinkedHashMap<>(r);
                r.put("factor", r.get("segment"));
            }
            Object factor = r.get("factor");
            Object share = r.get("share_pct");
            if (factor == null || share == null) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("factor", factor);
            p.put("share_pct", share);
            data.add(p);
        }
        return new ChartSpec(
                ChartSpec.ChartType.BAR,
                titleFromLabel(cd.label(), "Revenue factor contribution"),
                null,
                "factor",
                "share_pct",
                null,
                null,
                "percent",
                "category",
                data
        );
    }

    private ChartSpec buildSegmentBar(CollectedData cd, String categoryKey, String valueKey) {
        if (valueKey == null) return null;
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : cd.rows()) {
            if (!r.containsKey(categoryKey) || !r.containsKey(valueKey)) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put(categoryKey, r.get(categoryKey));
            p.put(valueKey, r.get(valueKey));
            data.add(p);
        }
        String fmt = valueKey.toLowerCase(Locale.ROOT).contains("share") ? "percent" : "number";
        return new ChartSpec(
                ChartSpec.ChartType.BAR,
                titleFromLabel(cd.label(), "Segment performance"),
                null,
                categoryKey,
                valueKey,
                null,
                null,
                fmt,
                "category",
                data
        );
    }

    private ChartSpec buildLine(CollectedData cd, String xKey, String yKey) {
        if (yKey == null) return null;
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : cd.rows()) {
            if (!r.containsKey(xKey) || !r.containsKey(yKey)) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put(xKey, r.get(xKey));
            p.put(yKey, r.get(yKey));
            data.add(p);
        }
        return new ChartSpec(
                ChartSpec.ChartType.LINE,
                titleFromLabel(cd.label(), "Trend"),
                null,
                null,
                null,
                xKey,
                yKey,
                "number",
                "date",
                data
        );
    }

    private ChartSpec buildCorridorBar(CollectedData cd) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : cd.rows()) {
            Object pickup = r.get("pickup");
            Object dropoff = r.get("dropoff");
            Object rev = r.containsKey("route_revenue") ? r.get("route_revenue") : r.get("metric_value");
            if (pickup == null || dropoff == null || rev == null) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("corridor", pickup + " → " + dropoff);
            p.put("route_revenue", rev);
            data.add(p);
        }
        return new ChartSpec(
                ChartSpec.ChartType.BAR,
                titleFromLabel(cd.label(), "Top corridors"),
                null,
                "corridor",
                "route_revenue",
                null,
                null,
                "currency",
                "category",
                data
        );
    }

    private ChartSpec buildComponentShare(CollectedData cd) {
        // Payload: summary row with component_share_pct etc
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> r = cd.rows().get(0);
        Object component = r.get("component_metric");
        Object share = r.get("component_share_pct");
        if (component == null || share == null) return null;
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("component", component);
        p1.put("share_pct", share);
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("component", "Other");
        try {
            double s = Double.parseDouble(share.toString());
            p2.put("share_pct", Math.max(0, 100.0 - s));
        } catch (Exception e) {
            p2.put("share_pct", null);
        }
        return new ChartSpec(
                ChartSpec.ChartType.DONUT,
                titleFromLabel(cd.label(), "Revenue mix"),
                null,
                "component",
                "share_pct",
                null,
                null,
                "percent",
                "category",
                List.of(p1, p2)
        );
    }

    private String guessValueKey(CollectedData cd, List<String> preferred) {
        if (cd.rows() == null || cd.rows().isEmpty()) return null;
        Map<String, Object> first = cd.rows().get(0);
        for (String k : preferred) {
            if (first.containsKey(k)) return k;
        }
        for (String k : first.keySet()) {
            String lower = k.toLowerCase(Locale.ROOT);
            if (lower.contains("revenue") || lower.contains("total") || lower.contains("value")) return k;
        }
        return null;
    }

    private CollectedData firstWithPrefix(List<CollectedData> collected, String prefixLower) {
        for (CollectedData cd : collected) {
            if (safeLower(cd.label()).startsWith(prefixLower)) return cd;
        }
        return null;
    }

    private String titleFromLabel(String label, String fallback) {
        if (label == null || label.isBlank()) return fallback;
        String t = label.replaceFirst("(?i)^(REVENUE:|DISCOVERY:|Trend:)\\s*", "").trim();
        if (t.length() > 80) t = t.substring(0, 77) + "...";
        return t.isBlank() ? fallback : t;
    }

    private String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private String firstLower(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        String v = list.get(0);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }
}

