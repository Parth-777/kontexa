package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-checks totals, shares, chart values, and narrative claims before UI output.
 */
@Component
public class AnalyticalConsistencyChecker {

    private static final Pattern PCT_IN_TEXT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final double PCT_TOLERANCE = 3.0;

    public ConsistencyResult check(GroundedAnalyticalFinding grounded) {
        List<String> issues = new ArrayList<>();
        if (grounded == null) return new ConsistencyResult(false, issues);

        AnalyticalFinding f = grounded.finding();
        String narrative = grounded.businessNarrative();

        switch (f) {
            case ContributionFinding c -> {
                issues.addAll(checkContributionConsistency(c, grounded.chartSpec(), narrative));
            }
            case RankingFinding r -> issues.addAll(checkChartEntityValues(r.rankedEntities().stream()
                    .map(e -> Map.entry(e.name(), e.value())).toList(),
                    grounded.chartSpec(), "value"));
            case ComparativeFinding c -> {
                if (narrative != null && narrative.contains("100%")
                        && Math.abs(c.deltaPct()) < 99) {
                    issues.add("Narrative claims 100% but finding delta is " + c.deltaPct());
                }
            }
            default -> {}
        }

        if (narrative != null && narrative.toLowerCase(Locale.ROOT).contains("100% of revenue")) {
            if (f instanceof ContributionFinding c && c.topContributorSharePct() < 99) {
                issues.add("Narrative claims 100% of revenue but top share is "
                        + c.topContributorSharePct());
            }
        }

        return new ConsistencyResult(issues.isEmpty(), issues);
    }

    private List<String> checkContributionConsistency(
            ContributionFinding c, ChartSpec chart, String narrative
    ) {
        List<String> issues = new ArrayList<>();
        double shareSum = c.segments().stream().mapToDouble(ContributionFinding.Segment::sharePct).sum();

        if (chart != null && chart.getData() != null) {
            double chartSum = 0;
            for (Map<String, Object> row : chart.getData()) {
                Object share = row.get("share");
                Object value = row.get("value");
                if (share != null) chartSum += parseDouble(share);
                if (value != null && !c.segments().isEmpty()) {
                    String key = String.valueOf(row.getOrDefault("segment", ""));
                    boolean matched = c.segments().stream()
                            .anyMatch(s -> s.name().equalsIgnoreCase(key)
                                    && Math.abs(s.value() - parseDouble(value)) < 1);
                    if (!key.isBlank() && !matched) {
                        issues.add("Chart value mismatch for segment: " + key);
                    }
                }
            }
            if (chartSum > 0 && Math.abs(chartSum - c.segments().stream()
                    .limit(chart.getData().size()).mapToDouble(ContributionFinding.Segment::sharePct).sum()) > PCT_TOLERANCE) {
                issues.add("Chart share values inconsistent with finding");
            }
        }

        if (narrative != null) {
            Matcher m = PCT_IN_TEXT.matcher(narrative);
            while (m.find()) {
                double claimed = Double.parseDouble(m.group(1));
                if (claimed >= 99 && c.topContributorSharePct() < claimed - PCT_TOLERANCE) {
                    issues.add("Narrative claims " + claimed + "% but top share is "
                            + c.topContributorSharePct());
                }
            }
        }

        if (shareSum < 90 || shareSum > 110) {
            issues.add("Finding shares sum to " + shareSum + "% — inconsistent totals");
        }

        return issues;
    }

    private List<String> checkChartEntityValues(
            List<Map.Entry<String, Double>> entities, ChartSpec chart, String valueKey
    ) {
        List<String> issues = new ArrayList<>();
        if (chart == null || chart.getData() == null) return issues;
        for (Map<String, Object> row : chart.getData()) {
            String entity = String.valueOf(row.getOrDefault("entity", row.get("segment")));
            double chartVal = parseDouble(row.get(valueKey));
            entities.stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(entity))
                    .findFirst()
                    .ifPresent(e -> {
                        if (Math.abs(e.getValue() - chartVal) / Math.max(1, e.getValue()) > 0.02) {
                            issues.add("Chart/finding value mismatch for " + entity);
                        }
                    });
        }
        return issues;
    }

    private double parseDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { return 0; }
    }

    public record ConsistencyResult(boolean consistent, List<String> issues) {}
}
