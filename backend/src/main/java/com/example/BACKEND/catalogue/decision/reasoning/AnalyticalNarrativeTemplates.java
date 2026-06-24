package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.presentation.BusinessLabelFormatter;
import com.example.BACKEND.catalogue.decision.presentation.executive.HumanNarrativeFormatter;
import com.example.BACKEND.catalogue.decision.presentation.executive.SemanticMetricFormatter;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Analyst-grade narrative templates — factual, concise, no consulting filler.
 */
@Component
public class AnalyticalNarrativeTemplates {

    private final HumanNarrativeFormatter human;
    private final SemanticMetricFormatter metrics;
    private final BusinessLabelFormatter labels;

    public AnalyticalNarrativeTemplates(
            HumanNarrativeFormatter human,
            SemanticMetricFormatter metrics,
            BusinessLabelFormatter labels
    ) {
        this.human = human;
        this.metrics = metrics;
        this.labels = labels;
    }

    public String headline(AnalyticalFinding finding) {
        return switch (finding) {
            case ContributionFinding c -> contributionHeadline(c);
            case RankingFinding r -> rankingHeadline(r);
            case ComparativeFinding c -> comparativeHeadline(c);
            case TemporalPatternFinding t -> temporalHeadline(t);
            case EfficiencyFinding e -> efficiencyHeadline(e);
            case CorrelationFinding c -> c.executiveSummary();
        };
    }

    public String support(AnalyticalFinding finding) {
        return switch (finding) {
            case ContributionFinding c -> contributionSupport(c);
            case RankingFinding r -> rankingSupport(r);
            case ComparativeFinding c -> comparativeSupport(c);
            case TemporalPatternFinding t -> temporalSupport(t);
            case EfficiencyFinding e -> efficiencySupport(e);
            case CorrelationFinding c -> c.interpretation();
        };
    }

    private String contributionHeadline(ContributionFinding c) {
        String metric = c.metricLabel() != null ? labels.formatMetric(c.metricLabel()) : "the metric";
        String leader = human.formatBucketPhrase(c.topContributor());
        if (c.concentrationRatio() >= 50 || c.topContributorSharePct() >= 30) {
            return String.format(Locale.ROOT,
                    "%s is heavily concentrated in %s, representing the dominant segment.",
                    metric, leader);
        }
        return String.format(Locale.ROOT,
                "%s leads with %s of total %s.",
                capitalize(human.formatBucketLabel(c.topContributor())),
                metrics.asSharePct(c.topContributorSharePct()),
                metric.toLowerCase(Locale.ROOT));
    }

    private String contributionSupport(ContributionFinding c) {
        if (c.segments().size() < 2) return "";
        String metric = c.metricLabel() != null ? c.metricLabel() : "value";
        String leader = human.formatBucketPhrase(c.topContributor());
        String tail = human.formatBucketPhrase(c.segments().getLast().name());
        if (c.leaderToTailRatio() >= 1.5) {
            return String.format(Locale.ROOT,
                    "Segments %s generate approximately %s more %s than segments %s.",
                    leader, metrics.asMultiple(c.leaderToTailRatio()),
                    metric.toLowerCase(Locale.ROOT), tail);
        }
        return "";
    }

    private String rankingHeadline(RankingFinding r) {
        if (r.rankedEntities().isEmpty()) return "Ranking analysis";
        return String.format(Locale.ROOT, "%s ranks highest on %s.",
                capitalize(human.formatBucketLabel(r.rankedEntities().getFirst().name())),
                r.metricLabel());
    }

    private String rankingSupport(RankingFinding r) {
        if (r.rankedEntities().size() < 2) return "";
        return String.format(Locale.ROOT, "Spread from leader to tail is %s.",
                metrics.asMultiple(r.leaderToTailMultiple()));
    }

    private String comparativeHeadline(ComparativeFinding c) {
        String metric = c.metricLabel() != null ? c.metricLabel() : "The metric";
        String leader = human.formatBucketPhrase(c.delta() >= 0 ? c.entityA() : c.entityB());
        return String.format(Locale.ROOT, "%s skews toward %s.", metric, leader);
    }

    private String comparativeSupport(ComparativeFinding c) {
        String metric = c.metricLabel() != null ? c.metricLabel().toLowerCase(Locale.ROOT) : "value";
        double mult = c.multiple() > 0 ? c.multiple() : (1 + Math.abs(c.deltaPct()) / 100.0);
        String a = human.formatBucketPhrase(c.entityA());
        String b = human.formatBucketPhrase(c.entityB());
        return String.format(Locale.ROOT,
                "%s generates approximately %s higher %s than %s.",
                c.delta() >= 0 ? a : b, metrics.asMultiple(mult), metric, c.delta() >= 0 ? b : a);
    }

    private String temporalHeadline(TemporalPatternFinding t) {
        return String.format(Locale.ROOT, "Peak activity occurs at %s.",
                human.formatBucketLabel(t.peakPeriod()));
    }

    private String temporalSupport(TemporalPatternFinding t) {
        return String.format(Locale.ROOT, "Momentum is %s across the observed window.",
                t.momentum() != null ? t.momentum().toLowerCase(Locale.ROOT) : "stable");
    }

    private String efficiencyHeadline(EfficiencyFinding e) {
        if (e.entries().isEmpty()) return "Efficiency analysis";
        return String.format(Locale.ROOT, "%s delivers the strongest yield per unit.",
                capitalize(human.formatBucketLabel(e.entries().getFirst().name())));
    }

    private String efficiencySupport(EfficiencyFinding e) {
        return String.format(Locale.ROOT, "Efficiency spread across segments is %s.",
                metrics.asMultiple(e.efficiencySpread()));
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
