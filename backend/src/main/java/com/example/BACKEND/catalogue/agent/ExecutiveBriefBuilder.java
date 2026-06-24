package com.example.BACKEND.catalogue.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a deterministic executive headline block from agent outputs (KPIs, anomalies, trends)
 * so the synthesis LLM leads with verified numbers — not a raw data dump.
 */
@Component
public class ExecutiveBriefBuilder {

    public String build(List<AgentDashboardResult.KpiCard> kpiCards,
                        List<AgentDashboardResult.Anomaly> anomalies,
                        List<CollectedData> collected) {
        List<String> lines = new ArrayList<>();

        if (kpiCards != null) {
            for (AgentDashboardResult.KpiCard kpi : kpiCards) {
                if (kpi.getMetric() == null || kpi.getMetric().isBlank()) continue;
                String arrow = switch (kpi.getDirection() != null ? kpi.getDirection() : "FLAT") {
                    case "UP"   -> "↑";
                    case "DOWN" -> "↓";
                    default     -> "→";
                };
                lines.add(String.format("• %s %s %.1f%% vs prior period (now %s, was %s)",
                        kpi.getMetric(), arrow, kpi.getChangePercent(),
                        kpi.getDisplayValue(), formatValue(kpi.getPreviousValue())));
            }
        }

        if (anomalies != null) {
            for (AgentDashboardResult.Anomaly a : anomalies) {
                if (a.getMetric() == null) continue;
                String dir = a.getDirection() != null ? a.getDirection() : "UP";
                lines.add(String.format("• ANOMALY — %s %s %.1f%%: %s",
                        a.getMetric(), dir, a.getChangePercent(),
                        truncate(a.getDescription(), 120)));
            }
        }

        appendTrendHeadlines(collected, lines);

        if (lines.isEmpty()) {
            return """
                    EXECUTIVE HEADLINES
                    ===================
                    (No pre-computed KPI deltas yet — derive insights only from COLLECTED DATA below.)
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("EXECUTIVE HEADLINES (pre-verified — prefer these numbers in card titles and metrics)\n");
        sb.append("================================================================================\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        sb.append("\nUse these as your top-priority storylines. Do not contradict them.\n");
        return sb.toString();
    }

    private void appendTrendHeadlines(List<CollectedData> collected, List<String> lines) {
        if (collected == null) return;
        int added = 0;
        for (CollectedData cd : collected) {
            if (added >= 3) break;
            String label = cd.label();
            if (label == null || !label.startsWith("Trend:")) continue;
            List<Map<String, Object>> rows = cd.rows();
            if (rows == null || rows.size() < 2) continue;

            String valKey = findNumericKey(rows.get(0));
            if (valKey == null) continue;

            double latest = toDouble(rows.get(0).get(valKey));
            double oldest = toDouble(rows.get(rows.size() - 1).get(valKey));
            if (oldest == 0) continue;

            double pct = ((latest - oldest) / Math.abs(oldest)) * 100.0;
            String metric = label.replace("Trend:", "").replace(" over time", "").trim();
            if (label.contains("(rollup)")) metric = metric.replace("(rollup)", "").trim();

            lines.add(String.format("• TREND — %s moved %.1f%% from earliest to latest period in series",
                    metric, pct));
            added++;
        }
    }

    private String findNumericKey(Map<String, Object> row) {
        for (String key : row.keySet()) {
            if (isNumeric(row.get(key))) return key;
        }
        return null;
    }

    private boolean isNumeric(Object v) {
        if (v instanceof Number) return true;
        if (v == null) return false;
        try {
            Double.parseDouble(v.toString());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private String formatValue(double v) {
        if (Math.abs(v) >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (Math.abs(v) >= 1_000)     return String.format("%.1fK", v / 1_000);
        return String.format("%.2f", v);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
