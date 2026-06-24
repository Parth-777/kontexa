package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link com.example.BACKEND.catalogue.agent.RevenueModelAgent} REVENUE:* datasets
 * into executive insight candidates for the revenue model lens.
 */
@Component
public class RevenueModelLensAgent {

    public List<InsightCandidate> generate(List<CollectedData> collected) {
        List<InsightCandidate> candidates = new ArrayList<>();

        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().startsWith("REVENUE:")) continue;

            String label = cd.label().toLowerCase();
            if (label.contains("total summary")) {
                addIfNonNull(candidates, fromTotalSummary(cd));
            } else if (label.contains("period-over-period")) {
                addIfNonNull(candidates, fromPeriodDelta(cd));
            } else if (label.contains("monthly trend")) {
                candidates.addAll(fromMonthlyTrend(cd));
            } else if (label.contains("sources by")) {
                addIfNonNull(candidates, fromTopSource(cd));
            } else if (label.contains("weak areas")) {
                addIfNonNull(candidates, fromWeakArea(cd));
            } else if (label.contains("factor model")) {
                addIfNonNull(candidates, fromFactorModel(cd));
            } else if (label.contains("top corridors")) {
                addIfNonNull(candidates, fromCorridor(cd));
            } else if (label.contains("component share")) {
                addIfNonNull(candidates, fromComponentShare(cd));
            }
        }
        return candidates;
    }

    private InsightCandidate fromTotalSummary(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> row = cd.rows().get(0);
        double total = toDouble(row.get("total_revenue"));
        double avg = toDouble(row.get("avg_revenue"));
        long count = toLong(row.get("trip_count"));

        return new InsightCandidate(
                "Revenue base: " + ExecutiveVoice.formatValue(total) + " across "
                        + ExecutiveVoice.formatValue(count) + " transactions",
                InsightLens.REVENUE, "", total / 1_000_000 + 10,
                "INFO", "MEDIUM",
                List.of(cd.label()),
                highlights(ExecutiveVoice.formatValue(total), ExecutiveVoice.formatValue(avg), "Window total"),
                List.of(extractMetric(cd.label())),
                "Finance", null
        );
    }

    private InsightCandidate fromPeriodDelta(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> row = cd.rows().get(0);
        double delta = toDouble(row.get("delta_pct"));
        String direction = str(row.get("direction"));
        String metric = ExecutiveVoice.humanizeMetric(str(row.get("metric")));

        if (Math.abs(delta) < 2) return null;

        return new InsightCandidate(
                metric + " revenue " + ("UP".equals(direction) ? "rose" : "fell") + " "
                        + fmtPct(Math.abs(delta)) + " vs prior month",
                InsightLens.REVENUE, "", Math.abs(delta) + 12,
                "UP".equals(direction) ? "OPPORTUNITY" : "RISK",
                Math.abs(delta) >= 10 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(str(row.get("current_period")), fmtPct(delta), "MoM trend"),
                List.of(metric),
                "Finance", null
        );
    }

    private List<InsightCandidate> fromMonthlyTrend(CollectedData cd) {
        List<InsightCandidate> out = new ArrayList<>();
        if (cd.rows().size() < 3) return out;

        double latest = toDouble(cd.rows().get(0).get("revenue"));
        double oldest = toDouble(cd.rows().get(cd.rows().size() - 1).get("revenue"));
        if (oldest <= 0) return out;

        double change = ((latest - oldest) / oldest) * 100.0;
        if (Math.abs(change) < 5) return out;

        out.add(new InsightCandidate(
                "Revenue trend " + (change > 0 ? "up" : "down") + " "
                        + fmtPct(Math.abs(change)) + " from earliest to latest month in window",
                InsightLens.REVENUE, "", Math.abs(change) + 8,
                change > 0 ? "OPPORTUNITY" : "RISK",
                Math.abs(change) >= 15 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(ExecutiveVoice.formatValue(latest), fmtPct(change), "Monthly series"),
                List.of(extractMetric(cd.label())),
                "Finance", null
        ));
        return out;
    }

    /** i) Where did most revenue come from */
    private InsightCandidate fromTopSource(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> top = cd.rows().get(0);
        String segment = str(top.get("segment"));
        double segRev = toDouble(top.get("segment_revenue"));
        double total = cd.rows().stream().mapToDouble(r -> toDouble(r.get("segment_revenue"))).sum();
        double share = total > 0 ? (segRev / total) * 100.0 : 0;

        String dim = extractDim(cd.label());
        String metric = extractMetricFromSourcesLabel(cd.label());

        return new InsightCandidate(
                "Largest revenue source: " + segment + " via " + ExecutiveVoice.humanizeMetric(dim)
                        + " at " + ExecutiveVoice.formatValue(segRev) + " (" + fmtPct(share) + " of top group)",
                InsightLens.REVENUE, "", share + segRev / 1_000_000,
                share >= 40 ? "RISK" : "OPPORTUNITY",
                share >= 40 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(segment, ExecutiveVoice.formatValue(segRev), ExecutiveVoice.humanizeMetric(dim)),
                List.of(metric, dim),
                "Finance", segment
        );
    }

    /** ii) Weak revenue areas */
    private InsightCandidate fromWeakArea(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> weak = cd.rows().get(0);
        String segment = str(weak.get("segment"));
        double segRev = toDouble(weak.get("segment_revenue"));
        String dim = extractDim(cd.label());

        return new InsightCandidate(
                "Underperforming segment: " + segment + " in " + ExecutiveVoice.humanizeMetric(dim)
                        + " at only " + ExecutiveVoice.formatValue(segRev) + " revenue",
                InsightLens.REVENUE, "", 15,
                "RISK", "MEDIUM",
                List.of(cd.label()),
                highlights(segment, ExecutiveVoice.formatValue(segRev), "Weak area"),
                List.of(dim),
                "Ops", segment
        );
    }

    /** v/vi) Strongest and weakest revenue factors */
    private InsightCandidate fromFactorModel(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> meta = cd.rows().get(0);
        if (!meta.containsKey("strongest_factor")) return null;

        String dim = ExecutiveVoice.humanizeMetric(str(meta.get("factor_dimension")));
        String strongest = str(meta.get("strongest_factor"));
        String weakest = str(meta.get("weakest_factor"));
        double strongPct = toDouble(meta.get("strongest_share_pct"));
        double weakPct = toDouble(meta.get("weakest_share_pct"));

        return new InsightCandidate(
                dim + " drives revenue mix — " + strongest + " contributes " + fmtPct(strongPct)
                        + " vs " + weakest + " at " + fmtPct(weakPct),
                InsightLens.REVENUE, "", strongPct + 10,
                strongPct >= 50 ? "RISK" : "INFO",
                strongPct >= 50 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(strongest, fmtPct(strongPct), dim),
                List.of(str(meta.get("factor_dimension"))),
                "Finance", strongest
        );
    }

    private InsightCandidate fromCorridor(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> top = cd.rows().get(0);
        String pickup = str(top.get("pickup"));
        String dropoff = str(top.get("dropoff"));
        double rev = toDouble(top.get("route_revenue"));

        return new InsightCandidate(
                "Highest-revenue corridor: " + pickup + " → " + dropoff
                        + " generated " + ExecutiveVoice.formatValue(rev),
                InsightLens.REVENUE, "", rev / 1_000_000 + 12,
                "OPPORTUNITY", "HIGH",
                List.of(cd.label()),
                highlights(pickup + "→" + dropoff, ExecutiveVoice.formatValue(rev), "Route"),
                List.of("pickup", "dropoff"),
                "Finance", pickup
        );
    }

    private InsightCandidate fromComponentShare(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> row = cd.rows().get(0);
        String component = ExecutiveVoice.humanizeMetric(str(row.get("component_metric")));
        double share = toDouble(row.get("component_share_pct"));
        if (share < 1) return null;

        return new InsightCandidate(
                component + " represents " + fmtPct(share) + " of total fare revenue in the model",
                InsightLens.REVENUE, "", share + 5,
                "INFO", "MEDIUM",
                List.of(cd.label()),
                highlights(component, fmtPct(share), "Revenue mix"),
                List.of(str(row.get("component_metric"))),
                "Finance", null
        );
    }

    private void addIfNonNull(List<InsightCandidate> list, InsightCandidate c) {
        if (c != null) list.add(c);
    }

    private String extractMetric(String label) {
        String[] parts = label.split(" ");
        return parts.length > 0 ? parts[parts.length - 1] : "revenue";
    }

    private String extractDim(String label) {
        int by = label.indexOf(" by ");
        if (by < 0) return "segment";
        String rest = label.substring(by + 4);
        int sp = rest.indexOf(' ');
        return sp > 0 ? rest.substring(0, sp) : rest;
    }

    private String extractMetricFromSourcesLabel(String label) {
        int last = label.lastIndexOf(' ');
        return last > 0 ? label.substring(last + 1) : "revenue";
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
