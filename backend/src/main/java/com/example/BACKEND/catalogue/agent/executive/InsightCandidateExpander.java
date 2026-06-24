package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds data-backed insight candidates from collected agent output so the feed
 * reliably reaches a minimum card count (even when only one lens fires).
 */
@Component
public class InsightCandidateExpander {

    public static final int MIN_CANDIDATES = 3;

    public List<InsightCandidate> supplement(
            List<InsightCandidate> existing,
            List<CollectedData> collected,
            List<AgentDashboardResult.KpiCard> kpiCards,
            List<AgentDashboardResult.Anomaly> anomalies) {

        List<InsightCandidate> out = new ArrayList<>(existing != null ? existing : List.of());
        Set<String> seen = new HashSet<>();
        for (InsightCandidate c : out) {
            seen.add(claimKey(c.claim()));
        }

        if (anomalies != null) {
            for (AgentDashboardResult.Anomaly a : anomalies) {
                add(out, seen, new InsightCandidate(
                        ExecutiveVoice.anomalyHeadline(a),
                        InsightLens.RISK, "", Math.abs(a.getChangePercent()) + 18,
                        "ALERT", "HIGH", List.of("Anomaly: " + a.getMetric()),
                        highlights(ExecutiveVoice.humanizeMetric(a.getMetric()),
                                fmtPct(a.getChangePercent()), a.getDirection()),
                        List.of(a.getMetric()), InsightLens.RISK.defaultOwner(), null));
            }
        }

        if (collected != null) {
            addFromMomDeltas(out, seen, collected);
            addFromConcentration(out, seen, collected);
            addFromTrends(out, seen, collected);
            addFromBreakdowns(out, seen, collected);
            addFromDistributions(out, seen, collected);
            addFromProfile(out, seen, collected);
            addFromVolumeTime(out, seen, collected);
        }

        if (kpiCards != null) {
            addFromKpis(out, seen, kpiCards);
        }

        return out;
    }

    private void addFromMomDeltas(List<InsightCandidate> out, Set<String> seen, List<CollectedData> collected) {
        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().contains("MoM delta")) continue;
            for (Map<String, Object> row : cd.rows()) {
                double delta = toDouble(row.get("delta_pct"));
                if (Math.abs(delta) < 2.0) continue;
                String metric = ExecutiveVoice.humanizeMetric(str(row.get("metric")));
                InsightLens lens = delta >= 0 ? InsightLens.GROWTH : InsightLens.RISK;
                String claim = metric + (delta >= 0 ? " rose " : " fell ")
                        + fmtPct(Math.abs(delta)) + " versus the prior period";
                add(out, seen, new InsightCandidate(
                        claim, lens, extractTable(cd.label()), Math.abs(delta) + 10,
                        delta >= 0 ? "OPPORTUNITY" : "RISK",
                        Math.abs(delta) >= 10 ? "HIGH" : "MEDIUM",
                        List.of(cd.label()),
                        highlights(metric, fmtPct(delta), "Period-over-period"),
                        List.of(metric), lens.defaultOwner(), null));
            }
        }
    }

    private void addFromConcentration(List<InsightCandidate> out, Set<String> seen, List<CollectedData> collected) {
        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().contains("concentration")) continue;
            for (Map<String, Object> row : cd.rows()) {
                double share = toDouble(row.get("top_share_pct"));
                if (share < 35) continue;
                String metric = ExecutiveVoice.humanizeMetric(str(row.get("metric")));
                String dim = ExecutiveVoice.humanizeMetric(str(row.get("dimension")));
                String segment = str(row.get("top_segment"));
                add(out, seen, new InsightCandidate(
                        segment + " accounts for " + fmtPct(share) + " of " + metric + " by " + dim,
                        InsightLens.RISK, extractTable(cd.label()), share / 2 + 8,
                        share >= 55 ? "RISK" : "INFO",
                        share >= 55 ? "HIGH" : "MEDIUM",
                        List.of(cd.label()),
                        highlights(segment, fmtPct(share), dim),
                        List.of(metric, dim), InsightLens.RISK.defaultOwner(), segment));
            }
        }
    }

    private void addFromTrends(List<InsightCandidate> out, Set<String> seen, List<CollectedData> collected) {
        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().startsWith("Trend:")) continue;
            if (cd.rows() == null || cd.rows().size() < 2) continue;

            double latest = toDouble(cd.rows().get(0).get("metric_value"));
            double prior  = toDouble(cd.rows().get(1).get("metric_value"));
            if (prior == 0) continue;

            double change = ((latest - prior) / Math.abs(prior)) * 100.0;
            if (Math.abs(change) < 3.0) continue;

            String metric = cd.label().replace("Trend:", "").replace(" over time", "").trim();
            metric = ExecutiveVoice.humanizeMetric(metric);
            InsightLens lens = change >= 0 ? InsightLens.GROWTH : InsightLens.RISK;

            add(out, seen, new InsightCandidate(
                    metric + (change >= 0 ? " trending up " : " trending down ")
                            + fmtPct(Math.abs(change)) + " in the latest period",
                    lens, "", Math.abs(change) + 8,
                    change >= 0 ? "OPPORTUNITY" : "RISK",
                    Math.abs(change) >= 12 ? "HIGH" : "MEDIUM",
                    List.of(cd.label()),
                    highlights(ExecutiveVoice.formatValue(latest), fmtPct(change), "Time series"),
                    List.of(metric), lens.defaultOwner(), null));
        }
    }

    private void addFromBreakdowns(List<InsightCandidate> out, Set<String> seen, List<CollectedData> collected) {
        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().contains(" breakdown by ")) continue;
            if (cd.rows() == null || cd.rows().isEmpty()) continue;

            double total = cd.rows().stream()
                    .mapToDouble(r -> toDouble(r.get("total")))
                    .sum();
            if (total <= 0) continue;

            Map<String, Object> top = cd.rows().get(0);
            String segment = firstKey(top, "segment", "dimension");
            double topVal = toDouble(top.get("total"));
            double share = (topVal / total) * 100.0;

            String[] parts = cd.label().split(" breakdown by ");
            String metric = parts.length > 0 ? ExecutiveVoice.humanizeMetric(parts[0].trim()) : "Metric";
            String dim = parts.length > 1 ? ExecutiveVoice.humanizeMetric(parts[1].trim()) : "Segment";

            if (share < 25) continue;

            add(out, seen, new InsightCandidate(
                    segment + " leads " + dim + " with " + fmtPct(share) + " of " + metric,
                    InsightLens.CUSTOMER, "", share / 2 + 6,
                    share >= 50 ? "RISK" : "INFO",
                    share >= 50 ? "MEDIUM" : "LOW",
                    List.of(cd.label()),
                    highlights(segment, fmtPct(share), dim),
                    List.of(metric, dim), InsightLens.CUSTOMER.defaultOwner(), segment));

            if (cd.rows().size() >= 2 && share >= 40) {
                Map<String, Object> second = cd.rows().get(1);
                String seg2 = firstKey(second, "segment", "dimension");
                double share2 = (toDouble(second.get("total")) / total) * 100.0;
                double gap = share - share2;
                if (gap >= 15) {
                    add(out, seen, new InsightCandidate(
                            "Gap between top and second " + dim + " is " + fmtPct(gap)
                                    + " — " + segment + " vs " + seg2,
                            InsightLens.RISK, "", gap + 5,
                            "RISK", gap >= 30 ? "HIGH" : "MEDIUM",
                            List.of(cd.label()),
                            highlights(segment, fmtPct(gap), dim),
                            List.of(dim), InsightLens.RISK.defaultOwner(), segment));
                }
            }
        }
    }

    private void addFromDistributions(List<InsightCandidate> out, Set<String> seen, List<CollectedData> collected) {
        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().startsWith("Distribution:")) continue;
            if (cd.rows() == null || cd.rows().size() < 2) continue;

            long total = cd.rows().stream()
                    .mapToLong(r -> toLong(firstKey(r, "count", "total", "records")))
                    .sum();
            if (total <= 0) continue;

            Map<String, Object> top = cd.rows().get(0);
            long topCount = toLong(firstKey(top, "count", "total", "records"));
            double share = (topCount * 100.0) / total;
            String dimVal = firstKey(top, "dimension_value", "category", "segment");
            String dim = cd.label().replace("Distribution:", "").trim();
            dim = ExecutiveVoice.humanizeMetric(dim);

            if (share < 30) continue;

            add(out, seen, new InsightCandidate(
                    dimVal + " represents " + fmtPct(share) + " of records by " + dim,
                    InsightLens.CUSTOMER, "", share / 2 + 5,
                    share >= 50 ? "RISK" : "INFO",
                    share >= 50 ? "MEDIUM" : "LOW",
                    List.of(cd.label()),
                    highlights(dimVal, fmtPct(share), dim),
                    List.of(dim), InsightLens.CUSTOMER.defaultOwner(), dimVal));
        }
    }

    private void addFromProfile(List<InsightCandidate> out, Set<String> seen, List<CollectedData> collected) {
        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().startsWith("PROFILE:") || !cd.label().contains("summary")) {
                continue;
            }
            for (Map<String, Object> row : cd.rows()) {
                long rows = toLong(row.get("row_count"));
                if (rows <= 0) continue;
                String minD = str(row.get("min_date"));
                String maxD = str(row.get("max_date"));
                String table = cd.label().replace("PROFILE:", "").replace("summary", "").trim();
                String claim = "Analysis covers " + ExecutiveVoice.formatValue(rows) + " records"
                        + (maxD.isBlank() ? "" : " through " + maxD);
                add(out, seen, new InsightCandidate(
                        claim, InsightLens.EFFICIENCY, table, 12,
                        "INFO", "LOW",
                        List.of(cd.label()),
                        highlights(ExecutiveVoice.formatValue(rows), minD.isBlank() ? "—" : minD + " → " + maxD, table),
                        List.of(), InsightLens.EFFICIENCY.defaultOwner(), null));
            }
        }
    }

    private void addFromVolumeTime(List<InsightCandidate> out, Set<String> seen, List<CollectedData> collected) {
        for (CollectedData cd : collected) {
            if (!"Volume over time".equals(cd.label()) || cd.rows() == null || cd.rows().size() < 2) continue;
            long latest = toLong(str(cd.rows().get(0).get("records")));
            long oldest = toLong(str(cd.rows().get(cd.rows().size() - 1).get("records")));
            if (oldest <= 0) continue;
            double change = ((latest - oldest) * 100.0) / oldest;
            if (Math.abs(change) < 5) continue;
            add(out, seen, new InsightCandidate(
                    "Trip volume " + (change > 0 ? "grew" : "declined") + " " + fmtPct(Math.abs(change))
                            + " from earliest to latest month in window",
                    change > 0 ? InsightLens.GROWTH : InsightLens.RISK, "",
                    Math.abs(change) + 6,
                    change > 0 ? "OPPORTUNITY" : "RISK",
                    Math.abs(change) >= 15 ? "MEDIUM" : "LOW",
                    List.of(cd.label()),
                    highlights(String.valueOf(latest), fmtPct(change), "Monthly volume"),
                    List.of("volume"), InsightLens.GROWTH.defaultOwner(), null));
        }
    }

    private void addFromKpis(List<InsightCandidate> out, Set<String> seen, List<AgentDashboardResult.KpiCard> kpiCards) {
        for (AgentDashboardResult.KpiCard kpi : kpiCards) {
            if (kpi.getChangePercent() == 0) continue;
            InsightLens lens = "UP".equals(kpi.getDirection()) ? InsightLens.GROWTH : InsightLens.RISK;
            String claim = ExecutiveVoice.humanizeMetric(kpi.getMetric()) + " "
                    + ("UP".equals(kpi.getDirection()) ? "increased" : "decreased")
                    + " " + fmtPct(Math.abs(kpi.getChangePercent())) + " vs prior period";
            add(out, seen, new InsightCandidate(
                    claim, lens, "", Math.abs(kpi.getChangePercent()) + 7,
                    "UP".equals(kpi.getDirection()) ? "OPPORTUNITY" : "RISK",
                    Math.abs(kpi.getChangePercent()) >= 10 ? "MEDIUM" : "LOW",
                    List.of("KPI: " + kpi.getMetric()),
                    highlights(kpi.getDisplayValue(), fmtPct(kpi.getChangePercent()), "KPI"),
                    List.of(kpi.getMetric()), lens.defaultOwner(), null));
        }
    }

    private void add(List<InsightCandidate> out, Set<String> seen, InsightCandidate candidate) {
        String key = claimKey(candidate.claim());
        if (seen.contains(key)) return;
        seen.add(key);
        out.add(candidate);
    }

    private String claimKey(String claim) {
        return claim == null ? "" : claim.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private String extractTable(String label) {
        if (label == null) return "";
        String rest = label.replace("EXEC:", "").replace("PROFILE:", "").trim();
        int space = rest.indexOf(' ');
        return space > 0 ? rest.substring(0, space) : rest;
    }

    private String firstKey(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            if (row.containsKey(k) && row.get(k) != null) return str(row.get(k));
        }
        for (Object v : row.values()) {
            if (v != null) return str(v);
        }
        return "";
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
