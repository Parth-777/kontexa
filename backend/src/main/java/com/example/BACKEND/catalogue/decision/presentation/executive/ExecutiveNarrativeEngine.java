package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.presentation.CorrelationAnalysisPayload;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import com.example.BACKEND.catalogue.decision.reasoning.StatisticalInterpretation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic executive narratives — delegates phrasing to {@link HumanNarrativeFormatter}.
 */
@Component
public class ExecutiveNarrativeEngine {

    private final BusinessSemanticAliases aliases;
    private final AnswerCompressionPolicy compression;
    private final HumanNarrativeFormatter human;
    private final SemanticMetricFormatter metrics;
    private final NarrativeCompressionLayer narrativeCompression;

    public ExecutiveNarrativeEngine(
            BusinessSemanticAliases aliases,
            AnswerCompressionPolicy compression,
            HumanNarrativeFormatter human,
            SemanticMetricFormatter metrics,
            NarrativeCompressionLayer narrativeCompression
    ) {
        this.aliases = aliases;
        this.compression = compression;
        this.human = human;
        this.metrics = metrics;
        this.narrativeCompression = narrativeCompression;
    }

    public NarrativeCompressionLayer.CompressedNarrative compressedNarrative(
            GroundedAnalyticalFinding primary, AnalyticalIntentType intent
    ) {
        return narrativeCompression.compress(primary, intent);
    }

    public String keyTakeaway(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) return "";
        return compression.compressParagraph(
                narrativeCompression.compress(primary, intent).keyTakeaway());
    }

    public String executiveSummary(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) return "";
        return compression.compressParagraph(
                narrativeCompression.compress(primary, intent).executiveSummary());
    }

    public String chartExplanation(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) return "";
        String evidence = narrativeCompression.compress(primary, intent).evidenceSentence();
        return evidence.isBlank() ? "" : compression.compressParagraph(evidence);
    }

    public String title(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) return "Analysis";
        return narrativeCompression.compress(primary, intent).chartTitle();
    }

    public List<ExecutiveSupportingMetric> metricsFromFinding(GroundedAnalyticalFinding primary) {
        if (primary == null) return List.of();
        return compression.compressMetrics(metricsFromFinding(primary.finding()));
    }

    private List<ExecutiveSupportingMetric> metricsFromFinding(AnalyticalFinding f) {
        List<ExecutiveSupportingMetric> out = new ArrayList<>();
        switch (f) {
            case ContributionFinding c -> {
                out.add(metric("Top segment",
                        human.formatBucketLabel(c.topContributor()), "",
                        metrics.asSharePct(c.topContributorSharePct()) + " share"));
                out.add(metric("Leader vs tail",
                        metrics.asMultiple(c.leaderToTailRatio()), "×", "revenue multiple"));
                out.add(metric("Top 3 concentration",
                        metrics.asSharePct(c.concentrationRatio()), "", "of revenue"));
            }
            case ComparativeFinding c -> {
                double multiple = c.multiple() > 0 ? c.multiple()
                        : (1.0 + Math.abs(c.deltaPct()) / 100.0);
                out.add(metric("Multiple", metrics.asMultiple(multiple), "×", "leader vs laggard"));
                out.add(metric("Gap",
                        metrics.formatValue(Math.abs(c.delta()), c.metricLabel()), "", ""));
            }
            case RankingFinding r -> {
                if (!r.rankedEntities().isEmpty()) {
                    out.add(metric("Leader",
                            human.formatBucketLabel(r.rankedEntities().getFirst().name()), "",
                            aliases.resolve(r.metricLabel())));
                    out.add(metric("Spread",
                            metrics.asMultiple(r.leaderToTailMultiple()), "×", "leader vs tail"));
                }
            }
            case EfficiencyFinding e -> {
                if (!e.entries().isEmpty()) {
                    out.add(metric("Best segment",
                            human.formatBucketLabel(e.entries().getFirst().name()), "", ""));
                    out.add(metric("Spread",
                            metrics.asMultiple(e.efficiencySpread()), "×", "efficiency"));
                }
            }
            case CorrelationFinding c -> {
                out.add(metric("Correlation coefficient",
                        String.format(Locale.ROOT, "%.3f", c.correlationCoefficient()), "", ""));
                out.add(metric("Strength",
                        CorrelationAnalysisPayload.formatStrength(c.strength()), "", ""));
                out.add(metric("Sample size",
                        String.format(Locale.ROOT, "%,d", c.sampleSize()), "observations", ""));
            }
            case TemporalPatternFinding t -> {
                out.add(metric("Peak",
                        human.formatBucketLabel(t.peakPeriod()), "",
                        metrics.formatValue(t.peakValue(), t.temporalDimension())));
                out.add(metric("Momentum", t.momentum(), "", ""));
            }
        }
        return out;
    }

    private ExecutiveSupportingMetric metric(String label, String value, String unit, String context) {
        return new ExecutiveSupportingMetric(label, value, unit, context);
    }
}
