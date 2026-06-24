package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.entity.SignalEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GrowthLensAgent {

    private static final double MIN_DELTA = 5.0;

    public List<InsightCandidate> generate(
            List<CollectedData> collected,
            List<AgentDashboardResult.KpiCard> kpiCards,
            List<SignalEntity> signals) {

        List<InsightCandidate> candidates = new ArrayList<>();

        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().startsWith("EXEC:") || !cd.label().contains("MoM delta")) {
                continue;
            }
            for (Map<String, Object> row : cd.rows()) {
                double delta = toDouble(row.get("delta_pct"));
                if (delta < MIN_DELTA) continue;

                String metric = ExecutiveVoice.humanizeMetric(str(row.get("metric")));
                String table  = extractTable(cd.label());
                String segment = str(row.get("driver_segment"));

                candidates.add(new InsightCandidate(
                        metric + " accelerated " + fmtPct(delta) + " period-over-period",
                        InsightLens.GROWTH,
                        table,
                        Math.abs(delta) + 10,
                        "OPPORTUNITY",
                        delta >= 15 ? "HIGH" : "MEDIUM",
                        List.of(cd.label()),
                        highlights(metric, fmtPct(delta), "Period-over-period"),
                        List.of(metric),
                        InsightLens.GROWTH.defaultOwner(),
                        segment
                ));
            }
        }

        if (kpiCards != null) {
            for (AgentDashboardResult.KpiCard kpi : kpiCards) {
                if (!"UP".equals(kpi.getDirection()) || kpi.getChangePercent() < MIN_DELTA) continue;
                candidates.add(new InsightCandidate(
                        kpi.getMetric() + " up " + fmtPct(kpi.getChangePercent()) + " vs prior period",
                        InsightLens.GROWTH,
                        "",
                        kpi.getChangePercent() + 8,
                        "OPPORTUNITY",
                        kpi.getChangePercent() >= 15 ? "HIGH" : "MEDIUM",
                        List.of("KPI: " + kpi.getMetric()),
                        highlights(kpi.getDisplayValue(), fmtPct(kpi.getChangePercent()), "KPI trend"),
                        List.of(kpi.getMetric()),
                        InsightLens.GROWTH.defaultOwner(),
                        null
                ));
            }
        }

        if (signals != null) {
            for (SignalEntity s : signals) {
                if (s.getDeltaPct() == null || s.getDeltaPct() < MIN_DELTA) continue;
                if (!"METRIC_SHIFT".equals(s.getSignalType())) continue;
                candidates.add(new InsightCandidate(
                        s.getColumnName() + " shifted " + fmtPct(s.getDeltaPct()) + " on " + s.getTableName(),
                        InsightLens.GROWTH,
                        s.getTableName(),
                        s.getDeltaPct() + ("HIGH".equals(s.getSignificance()) ? 15 : 8),
                        "OPPORTUNITY",
                        "HIGH".equals(s.getSignificance()) ? "HIGH" : "MEDIUM",
                        List.of("SIGNALS: material changes since baseline"),
                        highlights(s.getColumnName(), fmtPct(s.getDeltaPct()), s.getTableName()),
                        List.of(s.getColumnName()),
                        InsightLens.GROWTH.defaultOwner(),
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
        int idx = rest.indexOf(" MoM");
        return idx > 0 ? rest.substring(0, idx).trim() : rest;
    }

    private String fmtPct(double v) { return String.format("%.1f%%", v); }
    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }
    private String str(Object v) { return v == null ? "" : v.toString(); }
}
