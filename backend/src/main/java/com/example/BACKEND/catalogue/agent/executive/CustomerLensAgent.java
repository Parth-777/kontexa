package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CustomerLensAgent {

    public List<InsightCandidate> generate(List<CollectedData> collected) {
        List<InsightCandidate> candidates = new ArrayList<>();

        for (CollectedData cd : collected) {
            if (cd.label() == null) continue;

            if (cd.label().startsWith("Distribution:") && cd.rows().size() >= 2) {
                Map<String, Object> top = cd.rows().get(0);
                String dimVal = firstKeyValue(top, "dimension_value", "category", cd.label());
                long topCount = toLong(firstKeyValue(top, "count", "total", "0"));
                long total = cd.rows().stream()
                        .mapToLong(r -> toLong(firstKeyValue(r, "count", "total", "0")))
                        .sum();
                if (total > 0) {
                    double share = (topCount * 100.0) / total;
                    String dim = cd.label().replace("Distribution:", "").trim();
                    candidates.add(new InsightCandidate(
                            dimVal + " leads " + dim + " at " + fmtPct(share) + " of volume",
                            InsightLens.CUSTOMER,
                            "",
                            share / 2 + 5,
                            share >= 50 ? "RISK" : "INFO",
                            share >= 50 ? "MEDIUM" : "LOW",
                            List.of(cd.label()),
                            highlights(dimVal, fmtPct(share), dim),
                            List.of(dim),
                            InsightLens.CUSTOMER.defaultOwner(),
                            dimVal
                    ));
                }
            }

            if (cd.label().equals("Volume over time") && cd.rows().size() >= 3) {
                long latest = toLong(str(cd.rows().get(0).values().iterator().next()));
                long oldest = toLong(str(cd.rows().get(cd.rows().size() - 1).values().iterator().next()));
                if (oldest > 0) {
                    double change = ((latest - oldest) * 100.0) / oldest;
                    if (Math.abs(change) >= 10) {
                        candidates.add(new InsightCandidate(
                                "Customer activity volume " + (change > 0 ? "up" : "down")
                                        + " " + fmtPct(Math.abs(change)) + " across periods",
                                InsightLens.CUSTOMER,
                                "",
                                Math.abs(change) + 5,
                                change < 0 ? "RISK" : "OPPORTUNITY",
                                Math.abs(change) >= 20 ? "HIGH" : "MEDIUM",
                                List.of(cd.label()),
                                highlights(String.valueOf(latest), fmtPct(change), "Volume trend"),
                                List.of("volume"),
                                InsightLens.CUSTOMER.defaultOwner(),
                                null
                        ));
                    }
                }
            }
        }
        return candidates;
    }

    private String firstKeyValue(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            if (row.containsKey(k)) return str(row.get(k));
        }
        for (Object v : row.values()) return str(v);
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
    private long toLong(String s) {
        try { return (long) Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private long toLong(Object v) {
        return toLong(str(v));
    }
    private String str(Object v) { return v == null ? "" : v.toString(); }
}
