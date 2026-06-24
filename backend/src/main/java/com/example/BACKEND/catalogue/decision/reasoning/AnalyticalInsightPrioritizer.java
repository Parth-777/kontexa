package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Ranks findings by business impact, magnitude, anomaly strength, concentration, and trend severity.
 * Only top findings should reach the UI.
 */
@Component
public class AnalyticalInsightPrioritizer {

    public static final int DEFAULT_TOP_N = 4;

    private final StatisticalInterpretationEngine statsEngine;

    public AnalyticalInsightPrioritizer(StatisticalInterpretationEngine statsEngine) {
        this.statsEngine = statsEngine;
    }

    public List<ScoredFinding> prioritize(
            List<AnalyticalFinding> findings,
            AnalyticalDepthResult depth,
            AnalyticalIntentType intent,
            int topN
    ) {
        if (findings == null || findings.isEmpty()) return List.of();

        List<ScoredFinding> scored = new java.util.ArrayList<>();
        for (AnalyticalFinding f : findings) {
            StatisticalInterpretation s = statsEngine.interpret(f, depth, intent);
            scored.add(new ScoredFinding(f, s, score(f, s, intent)));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredFinding::priorityScore).reversed())
                .limit(topN)
                .toList();
    }

    public List<ScoredFinding> prioritize(
            List<AnalyticalFinding> findings,
            AnalyticalDepthResult depth,
            AnalyticalIntentType intent
    ) {
        return prioritize(findings, depth, intent, DEFAULT_TOP_N);
    }

    private double score(AnalyticalFinding f, StatisticalInterpretation s, AnalyticalIntentType intent) {
        double magnitude = normalizeMagnitude(f.magnitude());
        double businessImpact = businessImpactWeight(intent, f);
        double anomaly = s.hasOutliers() ? 0.9 : 0;
        double concentration = s.concentrated() ? s.concentrationScore() : 0;
        double trend = Math.abs(s.trendSlopeValue()) * 0.5;
        double variance = s.highVariance() ? 0.3 : 0;

        return magnitude * 0.30
                + businessImpact * 0.25
                + anomaly * 0.15
                + concentration * 0.15
                + trend * 0.10
                + variance * 0.05;
    }

    private double normalizeMagnitude(double m) {
        if (m <= 0) return 0.1;
        if (m > 100) return 1.0;
        return Math.min(1.0, m / 100.0);
    }

    private double businessImpactWeight(AnalyticalIntentType intent, AnalyticalFinding f) {
        return switch (intent) {
            case CONTRIBUTION, COMPOSITION -> 1.0;
            case EFFICIENCY -> 0.95;
            case TREND_ANALYSIS, ANOMALY_DETECTION -> 0.9;
            case RANKING, COMPARISON -> 0.85;
            case DISTRIBUTION, CORRELATION -> 0.75;
            default -> 0.7;
        };
    }

    public record ScoredFinding(
            AnalyticalFinding finding,
            StatisticalInterpretation statistics,
            double priorityScore
    ) {}
}
