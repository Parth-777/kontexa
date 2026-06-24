package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.entity.SignalEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RiskLensAgent {

    private static final double MIN_NEGATIVE_DELTA = -5.0;

    public List<InsightCandidate> generate(
            List<CollectedData> collected,
            List<AgentDashboardResult.Anomaly> anomalies,
            List<SignalEntity> signals) {

        List<InsightCandidate> candidates = new ArrayList<>();

        if (anomalies != null) {
            for (AgentDashboardResult.Anomaly a : anomalies) {
                double severity = Math.abs(a.getChangePercent());
                candidates.add(new InsightCandidate(
                        ExecutiveVoice.anomalyHeadline(a),
                        InsightLens.RISK,
                        "",
                        severity + 20,
                        "ALERT",
                        severity >= 20 ? "HIGH" : "MEDIUM",
                        List.of("Anomaly: " + a.getMetric()),
                        highlights(ExecutiveVoice.humanizeMetric(a.getMetric()),
                                fmtPct(a.getChangePercent()), a.getDirection()),
                        List.of(a.getMetric()),
                        InsightLens.RISK.defaultOwner(),
                        null
                ));
            }
        }

        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().contains("MoM delta")) continue;
            for (Map<String, Object> row : cd.rows()) {
                double delta = toDouble(row.get("delta_pct"));
                if (delta > MIN_NEGATIVE_DELTA) continue;

                String metric = str(row.get("metric"));
                String table  = extractTable(cd.label());
                candidates.add(new InsightCandidate(
                        metric + " declined " + fmtPct(Math.abs(delta)) + " period-over-period",
                        InsightLens.RISK,
                        table,
                        Math.abs(delta) + 12,
                        "ALERT",
                        Math.abs(delta) >= 15 ? "HIGH" : "MEDIUM",
                        List.of(cd.label()),
                        highlights(metric, fmtPct(delta), table),
                        List.of(metric),
                        InsightLens.RISK.defaultOwner(),
                        null
                ));
            }

            if (cd.label() != null && cd.label().contains("contribution by")) {
                for (Map<String, Object> row : cd.rows()) {
                    double share = toDouble(row.get("share_pct"));
                    if (share < 40) continue;
                    String segment = str(row.get("segment"));
                    candidates.add(new InsightCandidate(
                            segment + " drives " + fmtPct(share) + " of " + extractMetric(cd.label()),
                            InsightLens.RISK,
                            extractTable(cd.label()),
                            share / 2,
                            "RISK",
                            share >= 60 ? "HIGH" : "MEDIUM",
                            List.of(cd.label()),
                            highlights("Share", fmtPct(share), segment),
                            List.of("segment"),
                            InsightLens.RISK.defaultOwner(),
                            segment
                    ));
                }
            }
        }

        if (signals != null) {
            for (SignalEntity s : signals) {
                if (s.getDeltaPct() == null || s.getDeltaPct() > -5) continue;
                candidates.add(new InsightCandidate(
                        s.getColumnName() + " dropped " + fmtPct(Math.abs(s.getDeltaPct())) + " on " + s.getTableName(),
                        InsightLens.RISK,
                        s.getTableName(),
                        Math.abs(s.getDeltaPct()) + 10,
                        "ALERT",
                        "HIGH".equals(s.getSignificance()) ? "HIGH" : "MEDIUM",
                        List.of("SIGNALS: material changes since baseline"),
                        highlights(s.getColumnName(), fmtPct(s.getDeltaPct()), s.getTableName()),
                        List.of(s.getColumnName()),
                        InsightLens.RISK.defaultOwner(),
                        null
                ));
            }
        }
        return candidates;
    }

    private List<AgentDashboardResult.MetricHighlight> highlights(String a, String b, String c) {
        return List.of(
                new AgentDashboardResult.MetricHighlight("Size", a),
                new AgentDashboardResult.MetricHighlight("Change", b),
                new AgentDashboardResult.MetricHighlight("Where", c)
        );
    }

    private String extractTable(String label) {
        if (label == null) return "";
        String rest = label.replace("EXEC:", "").trim();
        int space = rest.indexOf(' ');
        return space > 0 ? rest.substring(0, space) : rest;
    }

    private String extractMetric(String label) {
        if (label == null) return "metric";
        int by = label.indexOf("contribution by");
        if (by < 0) return "metric";
        String mid = label.substring("EXEC:".length(), by).trim();
        String[] parts = mid.split(" ");
        return parts.length >= 2 ? parts[1] : "metric";
    }

    private String fmtPct(double v) { return String.format("%.1f%%", v); }
    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }
    private String str(Object v) { return v == null ? "" : v.toString(); }
}
