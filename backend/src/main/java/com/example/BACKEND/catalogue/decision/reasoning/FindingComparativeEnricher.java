package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalReasoningPlan;
import com.example.BACKEND.catalogue.decision.presentation.executive.HumanNarrativeFormatter;
import com.example.BACKEND.catalogue.decision.presentation.executive.SemanticMetricFormatter;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Comparative reasoning on structured findings — human-readable phrasing only.
 */
@Component
public class FindingComparativeEnricher {

    private final HumanNarrativeFormatter human;
    private final SemanticMetricFormatter metrics;

    public FindingComparativeEnricher(HumanNarrativeFormatter human, SemanticMetricFormatter metrics) {
        this.human = human;
        this.metrics = metrics;
    }

    public String enrich(AnalyticalFinding finding, AnalyticalReasoningPlan plan) {
        String mode = plan != null ? plan.comparisonMode() : "vs_peer_average";

        return switch (finding) {
            case ContributionFinding c -> enrichContribution(c, mode);
            case RankingFinding r -> human.enrichRanking(r);
            case TemporalPatternFinding t -> enrichTemporal(t, mode);
            case ComparativeFinding c -> human.enrichComparative(c);
            case EfficiencyFinding e -> enrichEfficiency(e, mode);
            case CorrelationFinding c -> c.interpretation();
        };
    }

    private String enrichContribution(ContributionFinding c, String mode) {
        String enriched = human.enrichContribution(c);
        if (!enriched.isBlank()) return enriched;
        String leader = human.formatBucketLabel(c.topContributor());
        return String.format(Locale.ROOT,
                "%s holds %s of revenue; top 3 segments account for %s.",
                leader, metrics.asSharePct(c.topContributorSharePct()),
                metrics.asSharePct(c.concentrationRatio()));
    }

    private String enrichTemporal(TemporalPatternFinding t, String mode) {
        if ("current_vs_previous".equals(mode) && t.periods().size() >= 2) {
            double latest = t.periods().getLast().value();
            double prior = t.periods().get(t.periods().size() - 2).value();
            double pct = prior != 0 ? ((latest - prior) / Math.abs(prior)) * 100 : 0;
            if (Math.abs(pct) >= 15) {
                return String.format(Locale.ROOT,
                        "Latest period moved %s vs prior; momentum is %s.",
                        metrics.asSharePct(Math.abs(pct)).replace("%", "pp"),
                        t.momentum().toLowerCase(Locale.ROOT));
            }
        }
        return String.format(Locale.ROOT,
                "Peak at %s (%s); trajectory is %s.",
                human.formatBucketLabel(t.peakPeriod()),
                metrics.formatValue(t.peakValue(), t.temporalDimension()),
                t.momentum().toLowerCase(Locale.ROOT));
    }

    private String enrichEfficiency(EfficiencyFinding e, String mode) {
        if (e.entries().isEmpty()) return e.executiveSummary();
        String top = human.formatBucketLabel(e.entries().getFirst().name());
        String bottom = human.formatBucketLabel(e.entries().getLast().name());
        return String.format(Locale.ROOT,
                "%s leads on yield (%s vs %s).",
                top, metrics.asMultiple(e.efficiencySpread()), bottom);
    }
}
