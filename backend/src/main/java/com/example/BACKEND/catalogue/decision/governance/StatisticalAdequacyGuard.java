package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Statistical significance safeguards — reject findings from tiny samples,
 * weak variance, or meaningless differences.
 */
@Component
public class StatisticalAdequacyGuard {

    private static final int MIN_ROW_COUNT = 30;
    private static final int MIN_GROUPS = 3;
    private static final double MIN_COEFFICIENT_OF_VARIATION = 0.05;
    private static final double MIN_MEANINGFUL_DELTA_PCT = 3.0;

    public AdequacyResult assess(
            AnalyticalFinding finding,
            MaterializedQueryResult materialized
    ) {
        List<String> issues = new ArrayList<>();
        int rowCount = materialized != null ? materialized.totalRows() : 0;

        switch (finding) {
            case ContributionFinding c -> {
                if (c.segments().size() < MIN_GROUPS) {
                    issues.add("Too few buckets for reliable contribution: " + c.segments().size());
                }
                // Pre-aggregated warehouse results often have few rows but many valid buckets.
                if (rowCount > 0 && rowCount < MIN_ROW_COUNT && c.segments().size() < MIN_GROUPS) {
                    issues.add("Insufficient sample: " + rowCount + " rows (min " + MIN_ROW_COUNT + ")");
                }
                double cv = coefficientOfVariation(c.segments().stream()
                        .mapToDouble(ContributionFinding.Segment::value).toArray());
                if (c.segments().size() >= MIN_GROUPS && cv < 0.01) {
                    issues.add("Near-flat distribution across buckets");
                }
            }
            case RankingFinding r -> {
                if (r.rankedEntities().size() < MIN_GROUPS) {
                    issues.add("Too few entities for ranking");
                }
                if (r.leaderToTailMultiple() < 1.05) {
                    issues.add("Leader-tail spread too small to be actionable");
                }
            }
            case ComparativeFinding c -> {
                if (Math.abs(c.deltaPct()) < MIN_MEANINGFUL_DELTA_PCT) {
                    issues.add("Delta below meaningful threshold: " + c.deltaPct() + "%");
                }
            }
            case EfficiencyFinding e -> {
                if (e.efficiencySpread() < 1.1) {
                    issues.add("Efficiency spread too narrow");
                }
            }
            case CorrelationFinding c -> {
                if (c.sampleSize() < 30) {
                    issues.add("Sample too small for correlation: " + c.sampleSize());
                }
            }
            case TemporalPatternFinding t -> {
                if (t.periods() == null || t.periods().size() < 3) {
                    issues.add("Insufficient periods for trend finding");
                }
            }
        }

        double strength = issues.isEmpty() ? 1.0 : Math.max(0.2, 1.0 - issues.size() * 0.25);
        return new AdequacyResult(issues.isEmpty(), issues, strength);
    }

    private double coefficientOfVariation(double[] values) {
        if (values.length < 2) return 0;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        if (mean == 0) return 0;
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        return Math.sqrt(var / values.length) / Math.abs(mean);
    }

    public record AdequacyResult(boolean adequate, List<String> issues, double statisticalStrength) {}
}
