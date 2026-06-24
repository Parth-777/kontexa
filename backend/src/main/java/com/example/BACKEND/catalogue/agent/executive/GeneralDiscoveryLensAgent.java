package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns {@link GeneralDiscoveryAgent} DISCOVERY:* datasets into executive insight candidates.
 */
@Component
public class GeneralDiscoveryLensAgent {

    public List<InsightCandidate> generate(List<CollectedData> collected) {
        List<InsightCandidate> candidates = new ArrayList<>();

        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().startsWith("DISCOVERY:")) continue;

            if (cd.label().contains("corridor") || cd.label().contains("route")) {
                candidates.addAll(fromCorridors(cd));
            } else if (cd.label().contains("concentration")) {
                InsightCandidate c = fromConcentration(cd);
                if (c != null) candidates.add(c);
            } else if (cd.label().contains("efficiency") || cd.label().contains("distance band")) {
                candidates.addAll(fromDistanceEfficiency(cd));
            } else if (cd.label().contains(" by ")) {
                InsightCandidate c = fromSegmentRanking(cd);
                if (c != null) candidates.add(c);
            }
        }
        return candidates;
    }

    private List<InsightCandidate> fromCorridors(CollectedData cd) {
        List<InsightCandidate> out = new ArrayList<>();
        if (cd.rows().isEmpty()) return out;

        Map<String, Object> top = cd.rows().get(0);
        String pickup = str(top.get("pickup"));
        String dropoff = str(top.get("dropoff"));
        double revenue = toDouble(top.get("route_revenue"));
        long trips = toLong(top.get("trips"));

        double totalRev = cd.rows().stream().mapToDouble(r -> toDouble(r.get("route_revenue"))).sum();
        double share = totalRev > 0 ? (revenue / totalRev) * 100.0 : 0;

        out.add(new InsightCandidate(
                "Top revenue corridor " + pickup + " → " + dropoff + " drives "
                        + ExecutiveVoice.formatValue(revenue) + " (" + fmtPct(share) + " of top routes)",
                InsightLens.GENERAL,
                "",
                share + revenue / 1_000_000,
                share >= 25 ? "OPPORTUNITY" : "INFO",
                share >= 30 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(pickup + "→" + dropoff, ExecutiveVoice.formatValue(revenue), fmtPct(share)),
                extractColumns(cd.label()),
                "Finance",
                pickup
        ));
        return out;
    }

    private InsightCandidate fromConcentration(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> row = cd.rows().get(0);
        String metric = ExecutiveVoice.humanizeMetric(str(row.get("metric")));
        String dim = ExecutiveVoice.humanizeMetric(str(row.get("dimension")));
        String segment = str(row.get("top_segment"));
        double share = toDouble(row.get("top_share_pct"));

        if (share < 35) return null;

        return new InsightCandidate(
                metric + " concentration: " + segment + " holds " + fmtPct(share) + " via " + dim,
                InsightLens.GENERAL,
                "",
                share / 2 + 10,
                share >= 55 ? "RISK" : "INFO",
                share >= 55 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(segment, fmtPct(share), dim),
                extractColumns(cd.label()),
                share >= 55 ? "Finance" : "Ops",
                segment
        );
    }

    private List<InsightCandidate> fromDistanceEfficiency(CollectedData cd) {
        List<InsightCandidate> out = new ArrayList<>();
        if (cd.rows().size() < 2) return out;

        Map<String, Object> best = cd.rows().get(0);
        Map<String, Object> worst = cd.rows().get(cd.rows().size() - 1);
        String bestBand = str(best.get("distance_band"));
        String worstBand = str(worst.get("distance_band"));
        double bestAvg = toDouble(best.get("avg_revenue"));
        double worstAvg = toDouble(worst.get("avg_revenue"));

        if (bestAvg <= 0) return out;

        out.add(new InsightCandidate(
                "Revenue per trip peaks at " + bestBand + " (" + ExecutiveVoice.formatValue(bestAvg)
                        + " avg) vs " + worstBand + " (" + ExecutiveVoice.formatValue(worstAvg) + ")",
                InsightLens.GENERAL,
                "",
                Math.abs(bestAvg - worstAvg) / bestAvg * 50 + 10,
                "OPPORTUNITY",
                "MEDIUM",
                List.of(cd.label()),
                highlights(bestBand, ExecutiveVoice.formatValue(bestAvg), "Distance band"),
                extractColumns(cd.label()),
                "Finance",
                bestBand
        ));
        return out;
    }

    private InsightCandidate fromSegmentRanking(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;

        String label = cd.label().replace("DISCOVERY:", "").trim();
        String[] parts = label.split(" by ");
        String metricPart = parts.length > 0 ? ExecutiveVoice.humanizeMetric(parts[0].trim()) : "Metric";
        String dimPart = parts.length > 1 ? ExecutiveVoice.humanizeMetric(parts[1].trim()) : "Segment";

        Map<String, Object> top = cd.rows().get(0);
        String segment = str(top.get("segment"));
        double value = toDouble(top.get("metric_value"));

        double total = cd.rows().stream().mapToDouble(r -> toDouble(r.get("metric_value"))).sum();
        double share = total > 0 ? (value / total) * 100.0 : 0;

        String claim = metricPart + " leader: " + segment + " at "
                + ExecutiveVoice.formatValue(value) + " (" + fmtPct(share) + " of top " + dimPart + " groups)";

        boolean tipOrFare = label.toLowerCase().contains("tip") || label.toLowerCase().contains("fare")
                || label.toLowerCase().contains("amount");

        return new InsightCandidate(
                claim,
                InsightLens.GENERAL,
                "",
                share + (tipOrFare ? 15 : 8),
                tipOrFare ? "OPPORTUNITY" : "INFO",
                share >= 40 || tipOrFare ? "MEDIUM" : "LOW",
                List.of(cd.label()),
                highlights(segment, ExecutiveVoice.formatValue(value), dimPart),
                extractColumns(cd.label()),
                tipOrFare ? "Finance" : "Ops",
                segment
        );
    }

    private List<String> extractColumns(String label) {
        List<String> cols = new ArrayList<>();
        if (label.contains(" by ")) {
            String rest = label.substring(label.indexOf(" by ") + 4);
            cols.add(rest.split(" ")[0]);
        }
        return cols;
    }

    private List<AgentDashboardResult.MetricHighlight> highlights(String a, String b, String c) {
        return List.of(
                new AgentDashboardResult.MetricHighlight("Size", a),
                new AgentDashboardResult.MetricHighlight("Change", b),
                new AgentDashboardResult.MetricHighlight("Where", c)
        );
    }

    private String fmtPct(double v) { return String.format("%.1f%%", v); }
    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(str(v)); } catch (Exception e) { return 0; }
    }
    private long toLong(Object v) {
        try { return (long) Double.parseDouble(str(v)); } catch (Exception e) { return 0; }
    }
    private String str(Object v) { return v == null ? "" : v.toString(); }
}
