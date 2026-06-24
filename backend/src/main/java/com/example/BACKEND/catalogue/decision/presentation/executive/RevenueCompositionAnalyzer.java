package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decomposes total revenue into fare, tips, and surcharge components when data is available.
 */
@Component
public class RevenueCompositionAnalyzer {

    private static final List<String> COMPONENT_KEYS = List.of(
            "fare_amount", "tip_amount", "tolls_amount", "extra", "extra_surcharge",
            "improvement_surcharge", "congestion_surcharge", "airport_fee", "cbd_congestion_fee"
    );

    private final BusinessSemanticAliases aliases;

    public RevenueCompositionAnalyzer(BusinessSemanticAliases aliases) {
        this.aliases = aliases;
    }

    public record RevenueComposition(
            double totalRevenue,
            List<ComponentShare> components,
            String dominantComponent,
            double dominantSharePct
    ) {
        public boolean hasData() {
            return totalRevenue > 0 && !components.isEmpty();
        }
    }

    public record ComponentShare(String key, String label, double amount, double sharePct) {}

    public boolean isRevenueQuestion(String question) {
        if (question == null) return false;
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("revenue") || q.contains("fare") || q.contains("pricing")
                || q.contains("tip") || q.contains("earning");
    }

    public RevenueComposition analyze(ComputationResultSet results) {
        if (results == null || results.results() == null) {
            return empty();
        }

        Map<String, Double> totals = new LinkedHashMap<>();
        for (QueryResult qr : results.results()) {
            if (qr.rows() == null) continue;
            for (Map<String, Object> row : qr.rows()) {
                accumulateRow(totals, row);
            }
        }

        double total = firstPositive(totals, "total_amount", "total_revenue", "revenue", "amount");
        if (total <= 0) {
            total = totals.values().stream().mapToDouble(Double::doubleValue).sum();
        }
        if (total <= 0) return empty();

        List<ComponentShare> shares = new ArrayList<>();
        for (String key : COMPONENT_KEYS) {
            double amt = totals.getOrDefault(key, 0.0);
            if (amt <= 0) continue;
            shares.add(new ComponentShare(
                    key,
                    aliases.resolve(key),
                    amt,
                    (amt / total) * 100.0
            ));
        }

        if (shares.isEmpty()) return empty();

        ComponentShare dominant = shares.stream()
                .max((a, b) -> Double.compare(a.sharePct(), b.sharePct()))
                .orElse(shares.getFirst());

        return new RevenueComposition(total, shares, dominant.label(), dominant.sharePct());
    }

    public List<ExecutiveSupportingMetric> toMetrics(RevenueComposition composition) {
        if (!composition.hasData()) return List.of();
        List<ExecutiveSupportingMetric> out = new ArrayList<>();
        for (ComponentShare c : composition.components()) {
            out.add(new ExecutiveSupportingMetric(
                    c.label() + " share",
                    String.format(Locale.ROOT, "%.1f", c.sharePct()),
                    "%",
                    "of total revenue"
            ));
        }
        out.add(new ExecutiveSupportingMetric(
                "Dominant component",
                composition.dominantComponent(),
                "",
                String.format(Locale.ROOT, "%.0f%% of revenue", composition.dominantSharePct())
        ));
        return out;
    }

    private void accumulateRow(Map<String, Double> totals, Map<String, Object> row) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            double val = toDouble(e.getValue());
            if (val <= 0) continue;
            if (COMPONENT_KEYS.contains(key) || key.contains("total") || key.contains("revenue")
                    || key.contains("fare") || key.contains("tip") || key.contains("toll")
                    || key.contains("surcharge") || key.contains("extra")) {
                totals.merge(key, val, Double::sum);
            }
        }
    }

    private double firstPositive(Map<String, Double> totals, String... keys) {
        for (String k : keys) {
            double v = totals.getOrDefault(k, 0.0);
            if (v > 0) return v;
        }
        return 0;
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString().replace(",", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private RevenueComposition empty() {
        return new RevenueComposition(0, List.of(), "", 0);
    }
}
