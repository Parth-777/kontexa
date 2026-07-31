package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chart recommendations derived only from {@link EvidencePack} request results.
 * EvidencePack remains the source of truth — no planner re-routing.
 */
@Component
public class EvidencePackChartRecommender {

    public record Recommendation(
            String requestId,
            String kind,
            String rationale,
            ChartSpec chart
    ) {}

    public List<Recommendation> recommend(EvidencePack pack) {
        List<Recommendation> out = new ArrayList<>();
        if (pack == null || pack.requestResults() == null) {
            return out;
        }

        Recommendation comparison = comparisonChart(pack);
        if (comparison != null) {
            out.add(comparison);
        }

        for (EvidencePack.RequestResult rr : pack.requestResults()) {
            if (rr == null || !rr.ok() || rr.rows() == null || rr.rows().isEmpty()) {
                continue;
            }
            if (isComparisonSlice(rr)) {
                continue; // already covered by paired comparison chart
            }
            ChartSpec chart = chartFor(rr, pack);
            if (chart != null && chart.getData() != null && !chart.getData().isEmpty()) {
                out.add(new Recommendation(
                        rr.requestId(),
                        rr.kind(),
                        rationaleFor(rr.kind()),
                        chart));
            }
        }
        return out;
    }

    public ChartSpec primary(EvidencePack pack) {
        List<Recommendation> all = recommend(pack);
        return all.isEmpty() ? null : all.getFirst().chart();
    }

    public List<Map<String, Object>> toPayload(List<Recommendation> recommendations) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Recommendation r : recommendations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("request_id", r.requestId());
            m.put("kind", r.kind());
            m.put("rationale", r.rationale());
            m.put("chart", chartToMap(r.chart()));
            out.add(m);
        }
        return out;
    }

    private ChartSpec chartFor(EvidencePack.RequestResult rr, EvidencePack pack) {
        String kind = rr.kind() == null ? "" : rr.kind().toLowerCase(Locale.ROOT);
        String metric = metricLabel(pack);
        return switch (kind) {
            case "correlation" -> correlationChart(rr, pack);
            case "time_series" -> lineChart(rr, metric + " over time");
            case "rank", "top_n", "bottom_n" -> barChart(rr, "Top contributors — " + metric, "value", false);
            case "share" -> barChart(rr, "Share of " + metric, valueKeyPreferringShare(rr), true);
            case "concentration" -> paretoChart(rr, "Concentration of " + metric);
            case "distribution" -> barChart(rr, "Distribution", "count", false);
            case "metric_by_dimension" -> metricByDimensionChart(rr, metric);
            case "slice_metric" -> singleValueBar(rr, metric);
            default -> null;
        };
    }

    private Recommendation comparisonChart(EvidencePack pack) {
        EvidencePack.RequestResult focal = null;
        EvidencePack.RequestResult baseline = null;
        for (EvidencePack.RequestResult rr : pack.requestResults()) {
            if (rr == null || !rr.ok()) {
                continue;
            }
            if ("FOCAL".equalsIgnoreCase(rr.role()) && hasValue(rr)) {
                focal = rr;
            }
            if ("BASELINE".equalsIgnoreCase(rr.role()) && hasValue(rr)) {
                baseline = rr;
            }
        }
        if (focal == null || baseline == null) {
            return null;
        }
        String metric = metricLabel(pack);
        List<Map<String, Object>> data = List.of(
                Map.of("member", "Focal", "value", number(first(focal).get("value")), "role", "FOCAL"),
                Map.of("member", "Baseline", "value", number(first(baseline).get("value")), "role", "BASELINE")
        );
        ChartSpec chart = new ChartSpec(
                ChartSpec.ChartType.GROUPED_BAR,
                metric + ": focal vs baseline",
                "Side-by-side comparison of the two investigated slices",
                "member", "value",
                null, null,
                "number", "category",
                data);
        return new Recommendation(
                focal.requestId() + "+" + baseline.requestId(),
                "slice_metric",
                "Paired FOCAL/BASELINE comparison → side-by-side bars",
                chart);
    }

    private ChartSpec correlationChart(EvidencePack.RequestResult rr, EvidencePack pack) {
        Map<String, Object> row = first(rr);
        double corr = number(row.get("correlation"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("coefficient", corr);
        payload.put("sample_size", row.getOrDefault("n", row.get("sample_size")));
        payload.put("strength", strength(corr));
        payload.put("direction", corr >= 0 ? "positive" : "negative");
        payload.put("source", pack.question() != null ? pack.question() : "x");
        payload.put("target", metricLabel(pack));
        return new ChartSpec(
                ChartSpec.ChartType.CORRELATION,
                "Relationship strength",
                strength(corr) + " " + (corr >= 0 ? "positive" : "negative")
                        + " association (r=" + String.format(Locale.US, "%.2f", corr) + ")",
                null, null, null, null,
                "number", "category",
                List.of(payload));
    }

    private ChartSpec lineChart(EvidencePack.RequestResult rr, String title) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rr.rows()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("period", row.get("period"));
            point.put("value", number(row.get("value")));
            data.add(point);
        }
        return new ChartSpec(
                ChartSpec.ChartType.LINE,
                title,
                "Trend from executed time_series evidence",
                null, null,
                "period", "value",
                "number", "date",
                data);
    }

    private ChartSpec barChart(
            EvidencePack.RequestResult rr,
            String title,
            String valueKey,
            boolean percent
    ) {
        List<Map<String, Object>> data = new ArrayList<>();
        int limit = Math.min(12, rr.rows().size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = rr.rows().get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("member", row.getOrDefault("member", "#" + (i + 1)));
            Object raw = row.containsKey(valueKey) ? row.get(valueKey) : row.get("value");
            double v = number(raw);
            if (percent && Math.abs(v) <= 1.0001) {
                v = v * 100.0;
            }
            point.put("value", v);
            data.add(point);
        }
        return new ChartSpec(
                ChartSpec.ChartType.HBAR,
                title,
                "From " + rr.kind() + " evidence (" + rr.rows().size() + " members)",
                "member", "value",
                null, null,
                percent ? "percent" : "number", "category",
                data);
    }

    private ChartSpec paretoChart(EvidencePack.RequestResult rr, String title) {
        List<Map<String, Object>> data = new ArrayList<>();
        int limit = Math.min(15, rr.rows().size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = rr.rows().get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("member", row.getOrDefault("member", "#" + (i + 1)));
            point.put("value", number(row.containsKey("value") ? row.get("value") : row.get("share")));
            if (row.containsKey("share")) {
                double share = number(row.get("share"));
                point.put("share", Math.abs(share) <= 1.0001 ? share * 100.0 : share);
            }
            if (row.containsKey("cumulative_share")) {
                double cum = number(row.get("cumulative_share"));
                point.put("cumulative_share", Math.abs(cum) <= 1.0001 ? cum * 100.0 : cum);
            }
            data.add(point);
        }
        ChartSpec chart = new ChartSpec(
                ChartSpec.ChartType.PARETO,
                title,
                "Value bars with cumulative share (Pareto)",
                "member", "value",
                null, null,
                "number", "category",
                data);
        return chart;
    }

    private ChartSpec metricByDimensionChart(EvidencePack.RequestResult rr, String metric) {
        Map<String, Object> first = first(rr);
        if (first.containsKey("focal_value") || first.containsKey("baseline_value")) {
            List<Map<String, Object>> data = new ArrayList<>();
            int limit = Math.min(10, rr.rows().size());
            for (int i = 0; i < limit; i++) {
                Map<String, Object> row = rr.rows().get(i);
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("member", row.getOrDefault("member", "#" + (i + 1)));
                point.put("focal", number(row.get("focal_value")));
                point.put("baseline", number(row.get("baseline_value")));
                point.put("value", number(row.get("focal_value")));
                data.add(point);
            }
            return new ChartSpec(
                    ChartSpec.ChartType.GROUPED_BAR,
                    metric + " by segment (focal vs baseline)",
                    "Grouped comparison across dimension members",
                    "member", "value",
                    null, null,
                    "number", "category",
                    data);
        }
        return barChart(rr, metric + " by segment", "value", false);
    }

    private ChartSpec singleValueBar(EvidencePack.RequestResult rr, String metric) {
        if (!hasValue(rr)) {
            return null;
        }
        String label = rr.role() != null ? rr.role() : rr.requestId();
        List<Map<String, Object>> data = List.of(
                Map.of("member", label, "value", number(first(rr).get("value"))));
        return new ChartSpec(
                ChartSpec.ChartType.BAR,
                metric,
                "Single-slice metric from evidence",
                "member", "value",
                null, null,
                "number", "category",
                data);
    }

    private boolean isComparisonSlice(EvidencePack.RequestResult rr) {
        return "slice_metric".equalsIgnoreCase(rr.kind())
                && rr.role() != null
                && ("FOCAL".equalsIgnoreCase(rr.role()) || "BASELINE".equalsIgnoreCase(rr.role()));
    }

    private String valueKeyPreferringShare(EvidencePack.RequestResult rr) {
        Map<String, Object> first = first(rr);
        if (first != null && first.containsKey("share")) {
            return "share";
        }
        return "value";
    }

    private String rationaleFor(String kind) {
        if (kind == null) {
            return "Evidence-derived chart";
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "correlation" -> "Correlation → relationship card / scatter summary";
            case "time_series" -> "Time series → line chart";
            case "rank", "top_n", "bottom_n" -> "Rank/top_n → horizontal bar chart";
            case "share" -> "Share → horizontal bar chart";
            case "concentration" -> "Concentration → Pareto chart";
            case "distribution" -> "Distribution → bar chart";
            case "metric_by_dimension" -> "Metric by dimension → bar/grouped bar";
            case "slice_metric" -> "Slice metric → comparison or single bar";
            default -> "Evidence kind " + kind + " → chart";
        };
    }

    private String metricLabel(EvidencePack pack) {
        if (pack.phenomenon() != null && pack.phenomenon().measure() != null
                && pack.phenomenon().measure().column() != null) {
            String col = pack.phenomenon().measure().column();
            String agg = pack.phenomenon().measure().aggregation();
            if (agg != null && !agg.isBlank()) {
                return agg.toUpperCase(Locale.ROOT) + "(" + col + ")";
            }
            return col;
        }
        return "metric";
    }

    private boolean hasValue(EvidencePack.RequestResult rr) {
        Map<String, Object> row = first(rr);
        return row != null && row.containsKey("value");
    }

    private Map<String, Object> first(EvidencePack.RequestResult rr) {
        if (rr == null || rr.rows() == null || rr.rows().isEmpty()) {
            return null;
        }
        return rr.rows().getFirst();
    }

    private double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String strength(double r) {
        double a = Math.abs(r);
        if (a >= 0.7) {
            return "strong";
        }
        if (a >= 0.4) {
            return "moderate";
        }
        if (a >= 0.2) {
            return "weak";
        }
        return "very weak";
    }

    private Map<String, Object> chartToMap(ChartSpec spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", spec.getType() != null ? spec.getType().name() : "BAR");
        m.put("title", spec.getTitle());
        m.put("subtitle", spec.getSubtitle());
        m.put("categoryKey", spec.getCategoryKey());
        m.put("valueKey", spec.getValueKey());
        m.put("xKey", spec.getXKey());
        m.put("yKey", spec.getYKey());
        m.put("valueFormat", spec.getValueFormat());
        m.put("xFormat", spec.getXFormat());
        m.put("data", spec.getData());
        return m;
    }
}
