package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.DistributionProfile;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Statistical interpretation layer: concentration, variance, skew, outliers, trend slope.
 */
@Component
public class StatisticalInterpretationEngine {

    private static final double CONCENTRATION_THRESHOLD = 0.45;
    private static final double SKEW_THRESHOLD = 0.5;
    private static final double VARIANCE_CV_THRESHOLD = 0.6;

    public StatisticalInterpretation interpret(
            AnalyticalFinding finding,
            AnalyticalDepthResult depth,
            AnalyticalIntentType intent
    ) {
        DistributionProfile dist = depth != null ? depth.distributionProfile() : null;

        return switch (finding) {
            case ContributionFinding c -> interpretContribution(c, dist);
            case RankingFinding r -> interpretRanking(r, dist);
            case TemporalPatternFinding t -> interpretTemporal(t, depth);
            case ComparativeFinding c -> interpretComparative(c);
            case EfficiencyFinding e -> interpretEfficiency(e, dist);
            case CorrelationFinding c -> interpretCorrelation(c);
        };
    }

    private StatisticalInterpretation interpretCorrelation(CorrelationFinding c) {
        double abs = Math.abs(c.correlationCoefficient());
        boolean meaningful = abs >= 0.2;
        return new StatisticalInterpretation(
                meaningful, abs, false, false, "NEUTRAL",
                false, 0, "FLAT", 0,
                c.executiveSummary() != null ? c.executiveSummary() : c.interpretation());
    }

    private StatisticalInterpretation interpretContribution(
            ContributionFinding c, DistributionProfile dist) {
        boolean concentrated = c.concentrationRatio() > 50 || c.giniCoefficient() > 0.55;
        double concScore = Math.max(c.concentrationRatio() / 100.0, c.giniCoefficient());
        boolean skewed = c.leaderToTailRatio() > 5;
        String skewDir = c.topContributorSharePct() > 40 ? "RIGHT" : "NEUTRAL";

        if (dist != null) {
            concentrated = concentrated || dist.concentrationIndex() > CONCENTRATION_THRESHOLD;
            skewed = skewed || Math.abs(dist.skewness()) > SKEW_THRESHOLD;
            if (dist.skewness() > SKEW_THRESHOLD) skewDir = "RIGHT";
            else if (dist.skewness() < -SKEW_THRESHOLD) skewDir = "LEFT";
        }

        String summary = buildContributionSummary(c, concentrated, skewed, skewDir);
        return new StatisticalInterpretation(
                concentrated, concScore, highVariance(dist), skewed, skewDir,
                c.leaderToTailRatio() > 10, c.leaderToTailRatio() > 10 ? 1 : 0,
                "FLAT", 0, summary);
    }

    private StatisticalInterpretation interpretRanking(RankingFinding r, DistributionProfile dist) {
        boolean concentrated = r.leaderToTailMultiple() > 5;
        boolean skewed = r.leaderToMedianMultiple() > 2.5;
        double cv = dist != null && dist.mean() > 0 ? dist.stdDev() / dist.mean() : 0;
        boolean highVar = cv > VARIANCE_CV_THRESHOLD;

        String summary = String.format(
                "%s leads with %.1fx the tail value; distribution is %s.",
                r.rankedEntities().isEmpty() ? "Top entity" : r.rankedEntities().getFirst().name(),
                r.leaderToTailMultiple(),
                concentrated ? "highly concentrated" : "relatively dispersed");

        return new StatisticalInterpretation(
                concentrated, r.leaderToTailMultiple() / 10.0, highVar, skewed,
                skewed ? "RIGHT" : "NEUTRAL",
                r.leaderToTailMultiple() > 8, r.leaderToTailMultiple() > 8 ? 1 : 0,
                "FLAT", 0, summary);
    }

    private StatisticalInterpretation interpretTemporal(
            TemporalPatternFinding t, AnalyticalDepthResult depth) {
        String slope = t.momentum() != null ? t.momentum().toUpperCase(Locale.ROOT) : "FLAT";
        double slopeVal = switch (slope) {
            case "RISING", "ACCELERATING" -> 1.0;
            case "DECLINING", "DECELERATING" -> -1.0;
            default -> 0.0;
        };

        boolean hasInflection = depth != null && !depth.inflectionPoints().isEmpty();
        String summary = String.format(
                "Trend slope is %s; peak at %s (%.0f).%s",
                slope.toLowerCase(Locale.ROOT), t.peakPeriod(), t.peakValue(),
                hasInflection ? " Inflection detected in underlying series." : "");

        return new StatisticalInterpretation(
                false, 0, false, false, "NEUTRAL", false, 0,
                slope, slopeVal, summary);
    }

    private StatisticalInterpretation interpretComparative(ComparativeFinding c) {
        boolean unusual = Math.abs(c.deltaPct()) > 25;
        String summary = String.format(
                "%s vs %s: %.1f%% gap (%s direction).",
                c.entityA(), c.entityB(), Math.abs(c.deltaPct()),
                c.deltaPct() >= 0 ? "positive" : "negative");

        return new StatisticalInterpretation(
                Math.abs(c.deltaPct()) > 40, Math.abs(c.deltaPct()) / 100.0,
                unusual, unusual, c.deltaPct() > 0 ? "RIGHT" : "LEFT",
                unusual, unusual ? 1 : 0, "FLAT", 0, summary);
    }

    private StatisticalInterpretation interpretEfficiency(
            EfficiencyFinding e, DistributionProfile dist) {
        boolean concentrated = e.efficiencySpread() > 3;
        boolean highVar = dist != null && dist.mean() > 0
                && dist.stdDev() / dist.mean() > VARIANCE_CV_THRESHOLD;

        String summary = String.format(
                "Efficiency spread is %.1fx; top performer %s vs cohort average.",
                e.efficiencySpread(),
                e.entries().isEmpty() ? "unknown" : e.entries().getFirst().name());

        return new StatisticalInterpretation(
                concentrated, e.efficiencySpread() / 10.0, highVar,
                e.efficiencySpread() > 2, "RIGHT",
                e.efficiencySpread() > 5, e.efficiencySpread() > 5 ? 1 : 0,
                "FLAT", 0, summary);
    }

    private boolean highVariance(DistributionProfile dist) {
        if (dist == null || dist.mean() == 0) return false;
        return dist.stdDev() / dist.mean() > VARIANCE_CV_THRESHOLD;
    }

    private String buildContributionSummary(
            ContributionFinding c, boolean concentrated, boolean skewed, String skewDir) {
        String concPhrase = concentrated
                ? "Revenue is highly concentrated"
                : "Revenue is moderately distributed";
        String skewPhrase = skewed
                ? " with a " + skewDir.toLowerCase(Locale.ROOT) + "-skewed long tail"
                : "";
        return concPhrase + " in " + c.topContributor() + " (" +
                String.format(Locale.ROOT, "%.0f", c.topContributorSharePct()) + "% share)" + skewPhrase + ".";
    }
}
