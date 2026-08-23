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
        MetricSemantics sem = MetricEvidenceAnnotator.fromRow(row);
        double total = toDouble(row.get("total_revenue"));
        double avg = toDouble(row.get("avg_revenue"));
        long count = toLong(row.containsKey("row_count") ? row.get("row_count") : row.get("trip_count"));
        String grain = sem.resolvedGrainLabel();

        String totalDisp = MetricDisplayFormatter.formatMetric(total, sem);
        String avgDisp = MetricDisplayFormatter.formatMetric(avg, sem);
        String countDisp = MetricDisplayFormatter.formatCount(count, grain);

        // Relative materiality only — no raw magnitude across units
        double impact = 20;

        return new InsightCandidate(
                "Revenue base: " + totalDisp + " across " + countDisp + " " + grain,
                InsightLens.REVENUE, "", impact,
                "INFO", "MEDIUM",
                List.of(cd.label()),
                highlights(totalDisp, avgDisp, "Window total"),
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
                InsightLens.REVENUE, "", Math.min(40, Math.abs(delta)),
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

        MetricSemantics sem = MetricEvidenceAnnotator.fromRow(cd.rows().get(0));
        double latest = toDouble(cd.rows().get(0).get("revenue"));
        double oldest = toDouble(cd.rows().get(cd.rows().size() - 1).get("revenue"));
        if (oldest <= 0) return out;

        double change = ((latest - oldest) / oldest) * 100.0;
        if (Math.abs(change) < 5) return out;

        out.add(new InsightCandidate(
                "Revenue trend " + (change > 0 ? "up" : "down") + " "
                        + fmtPct(Math.abs(change)) + " from earliest to latest month in window",
                InsightLens.REVENUE, "", Math.min(40, Math.abs(change)),
                change > 0 ? "OPPORTUNITY" : "RISK",
                Math.abs(change) >= 15 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(MetricDisplayFormatter.formatMetric(latest, sem), fmtPct(change), "Monthly series"),
                List.of(extractMetric(cd.label())),
                "Finance", null
        ));
        return out;
    }

    /** i) Where did most revenue come from — share must be vs grand total */
    private InsightCandidate fromTopSource(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> top = cd.rows().get(0);
        if (!MetricEvidenceAnnotator.hasGrandTotalShare(top)) return null;

        String segment = str(top.get("segment"));
        double segRev = toDouble(top.get("segment_revenue"));
        double share = toDouble(top.get(MetricEvidenceAnnotator.SHARE_OF_TOTAL));
        int segments = (int) toDouble(top.get(MetricEvidenceAnnotator.SEGMENT_COUNT));
        if (segments < 2) return null; // need comparative segment evidence

        // Dominance/concentration language requires concentration gate
        boolean dominance = share >= 40
                && MetricEvidenceAnnotator.concentrationEvidenceOk(top, segments);

        MetricSemantics sem = MetricEvidenceAnnotator.fromRow(top);
        String dim = extractDim(cd.label());
        String metric = extractMetricFromSourcesLabel(cd.label());
        String valDisp = MetricDisplayFormatter.formatMetric(segRev, sem);

        String claim;
        if (dominance) {
            claim = "Largest revenue source: " + segment + " via " + ExecutiveVoice.humanizeMetric(dim)
                    + " at " + valDisp + " (" + fmtPct(share) + " of total)";
        } else if (share > 0) {
            claim = "Top revenue segment: " + segment + " via " + ExecutiveVoice.humanizeMetric(dim)
                    + " at " + valDisp + " (" + fmtPct(share) + " of total)";
        } else {
            return null;
        }

        return new InsightCandidate(
                claim,
                InsightLens.REVENUE, "", Math.min(50, share),
                dominance ? "RISK" : "OPPORTUNITY",
                share >= 40 ? "HIGH" : "MEDIUM",
                List.of(cd.label()),
                highlights(segment, valDisp, ExecutiveVoice.humanizeMetric(dim)),
                List.of(dim, metric),
                "Finance", segment
        );
    }

    private InsightCandidate fromWeakArea(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> weak = cd.rows().get(0);
        String segment = str(weak.get("segment"));
        double segRev = toDouble(weak.get("segment_revenue"));
        MetricSemantics sem = MetricEvidenceAnnotator.fromRow(weak);
        String dim = extractDim(cd.label());

        return new InsightCandidate(
                "Underperforming segment: " + segment + " in " + ExecutiveVoice.humanizeMetric(dim)
                        + " at only " + MetricDisplayFormatter.formatMetric(segRev, sem) + " revenue",
                InsightLens.REVENUE, "", 15,
                "RISK", "MEDIUM",
                List.of(cd.label()),
                highlights(segment, MetricDisplayFormatter.formatMetric(segRev, sem), "Weak area"),
                List.of(dim),
                "Ops", segment
        );
    }

    private InsightCandidate fromFactorModel(CollectedData cd) {
        if (cd.rows().isEmpty()) return null;
        Map<String, Object> meta = cd.rows().get(0);
        if (!meta.containsKey("strongest_factor")) return null;
        if (!MetricEvidenceAnnotator.hasGrandTotalShare(meta)
                && !meta.containsKey(MetricEvidenceAnnotator.SHARE_OF_TOTAL)) {
            // require grand-total based strongest_share_pct
            if (!meta.containsKey("strongest_share_pct")) return null;
        }

        int segmentCount = meta.containsKey(MetricEvidenceAnnotator.SEGMENT_COUNT)
                ? (int) toDouble(meta.get(MetricEvidenceAnnotator.SEGMENT_COUNT))
                : Math.max(0, cd.rows().size() - 1);

        Map<String, Object> gateRow = new java.util.LinkedHashMap<>(meta);
        if (!gateRow.containsKey(MetricEvidenceAnnotator.SHARE_OF_TOTAL)
                && meta.containsKey("strongest_share_pct")) {
            gateRow.put(MetricEvidenceAnnotator.SHARE_OF_TOTAL, meta.get("strongest_share_pct"));
        }
        if (!MetricEvidenceAnnotator.concentrationEvidenceOk(gateRow, segmentCount)) {
            return null;
        }

        String dim = ExecutiveVoice.humanizeMetric(str(meta.get("factor_dimension")));
        String strongest = str(meta.get("strongest_factor"));
        String weakest = str(meta.get("weakest_factor"));
        double strongPct = toDouble(meta.get("strongest_share_pct"));
        double weakPct = toDouble(meta.get("weakest_share_pct"));

        return new InsightCandidate(
                dim + " mix — " + strongest + " contributes " + fmtPct(strongPct)
                        + " of total vs " + weakest + " at " + fmtPct(weakPct),
                InsightLens.REVENUE, "", Math.min(50, strongPct),
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
        MetricSemantics sem = MetricEvidenceAnnotator.fromRow(top);

        return new InsightCandidate(
                "Highest-revenue corridor: " + pickup + " → " + dropoff
                        + " generated " + MetricDisplayFormatter.formatMetric(rev, sem),
                InsightLens.REVENUE, "", 18,
                "OPPORTUNITY", "HIGH",
                List.of(cd.label()),
                highlights(pickup + "→" + dropoff, MetricDisplayFormatter.formatMetric(rev, sem), "Route"),
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
                component + " represents " + fmtPct(share) + " of primary revenue in the model",
                InsightLens.REVENUE, "", Math.min(40, share),
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
