package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class EfficiencyLensAgent {

    private static final Set<String> COST_KEYWORDS = Set.of(
            "cost", "spend", "expense", "cac", "fulfillment", "shipping", "margin", "cogs");

    public List<InsightCandidate> generate(List<CollectedData> collected) {
        List<InsightCandidate> candidates = new ArrayList<>();

        for (CollectedData cd : collected) {
            if (cd.label() == null || !cd.label().startsWith("EXEC:")) continue;
            String labelLower = cd.label().toLowerCase();
            boolean costRelated = COST_KEYWORDS.stream().anyMatch(labelLower::contains);
            if (!costRelated && !labelLower.contains("mom delta")) continue;

            for (Map<String, Object> row : cd.rows()) {
                if (!row.containsKey("delta_pct")) continue;
                double delta = toDouble(row.get("delta_pct"));
                String metric = str(row.get("metric"));
                if (!isCostMetric(metric) && !costRelated) continue;

                boolean worsening = metric.toLowerCase().contains("cost") ? delta > 5 : delta < -5;
                if (!worsening) continue;

                candidates.add(new InsightCandidate(
                        "Unit economics pressure: " + metric + " moved " + fmtPct(delta),
                        InsightLens.EFFICIENCY,
                        extractTable(cd.label()),
                        Math.abs(delta) + 8,
                        "RISK",
                        Math.abs(delta) >= 10 ? "HIGH" : "MEDIUM",
                        List.of(cd.label()),
                        highlights(metric, fmtPct(delta), "Efficiency"),
                        List.of(metric),
                        InsightLens.EFFICIENCY.defaultOwner(),
                        null
                ));
            }
        }
        return candidates;
    }

    private boolean isCostMetric(String metric) {
        if (metric == null) return false;
        String m = metric.toLowerCase();
        return COST_KEYWORDS.stream().anyMatch(m::contains);
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
        return label.replace("EXEC:", "").split(" ")[0].trim();
    }

    private String fmtPct(double v) { return String.format("%.1f%%", v); }
    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }
    private String str(Object v) { return v == null ? "" : v.toString(); }
}
