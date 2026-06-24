package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import com.example.BACKEND.catalogue.decision.reasoning.StatisticalInterpretation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converts analytical findings into calm, readable business prose.
 * Bloomberg-terminal style — no robotic comparatives or metric repetition.
 */
@Component
public class HumanNarrativeFormatter {

    private static final Pattern DISTANCE_BUCKET = Pattern.compile("^\\d+-\\d+$");
    private static final Pattern DISTANCE_TAIL   = Pattern.compile("^\\d+\\+$");
    private static final Pattern HOUR_BUCKET     = Pattern.compile("^\\d{1,2}$");

    private final BusinessSemanticAliases aliases;
    private final SemanticMetricFormatter metrics;

    public HumanNarrativeFormatter(BusinessSemanticAliases aliases, SemanticMetricFormatter metrics) {
        this.aliases = aliases;
        this.metrics = metrics;
    }

    public String headline(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) return "";
        return switch (primary.finding()) {
            case ContributionFinding c -> contributionHeadline(c);
            case ComparativeFinding c -> comparativeHeadline(c);
            case CorrelationFinding c -> correlationHeadline(c);
            case RankingFinding r -> rankingHeadline(r);
            case TemporalPatternFinding t -> temporalHeadline(t);
            case EfficiencyFinding e -> efficiencyHeadline(e);
        };
    }

    public List<String> supportingInsights(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) return List.of();
        return switch (primary.finding()) {
            case ContributionFinding c -> contributionSupport(c);
            case ComparativeFinding c -> List.of(comparativeSupport(c));
            case CorrelationFinding c -> List.of(correlationSupport(c));
            case RankingFinding r -> rankingSupport(r);
            case TemporalPatternFinding t -> List.of(temporalSupport(t));
            case EfficiencyFinding e -> List.of(efficiencySupport(e));
        };
    }

    public String evidenceSentence(GroundedAnalyticalFinding primary, AnalyticalIntentType intent) {
        if (primary == null) return "";
        return switch (primary.finding()) {
            case ContributionFinding c -> contributionEvidence(c);
            case ComparativeFinding c -> comparativeEvidence(c);
            case CorrelationFinding c -> correlationEvidence(c);
            case RankingFinding r -> rankingEvidence(r);
            case TemporalPatternFinding t -> temporalEvidence(t);
            case EfficiencyFinding e -> efficiencyEvidence(e);
        };
    }

    public String chartTitle(AnalyticalFinding finding) {
        return switch (finding) {
            case ContributionFinding c ->
                    aliases.resolve(c.metricLabel()) + " by " + aliases.resolve(c.dimensionLabel());
            case RankingFinding r ->
                    aliases.resolve(r.metricLabel()) + " by " + aliases.resolve(r.groupingLabel());
            case ComparativeFinding c ->
                    aliases.resolve(c.metricLabel()) + " comparison";
            case CorrelationFinding c ->
                    c.sourceVariable() + " vs " + c.targetVariable();
            case TemporalPatternFinding t ->
                    aliases.resolve(t.temporalDimension()) + " trend";
            case EfficiencyFinding e ->
                    aliases.resolve(e.numeratorLabel()) + " by " + aliases.resolve(e.groupingLabel());
        };
    }

    public String formatBucketLabel(String raw) {
        if (raw == null || raw.isBlank()) return "this segment";
        String s = raw.trim();
        if (DISTANCE_BUCKET.matcher(s).matches()) {
            return s.replace('-', '–') + " miles";
        }
        if (DISTANCE_TAIL.matcher(s).matches()) {
            return s + " miles";
        }
        if (HOUR_BUCKET.matcher(s).matches()) {
            int hour = Integer.parseInt(s);
            if (hour == 0) return "midnight";
            if (hour == 12) return "noon";
            if (hour < 12) return hour + " AM";
            if (hour == 24) return "midnight";
            return (hour - 12) + " PM";
        }
        if ("weekend".equalsIgnoreCase(s)) return "weekends";
        if ("weekday".equalsIgnoreCase(s)) return "weekdays";
        return aliases.resolveSegment(s);
    }

    public String formatBucketPhrase(String bucket) {
        if (bucket == null || bucket.isBlank()) return "this segment";
        String s = bucket.trim();
        if (DISTANCE_BUCKET.matcher(s).matches()) {
            String[] parts = s.split("-");
            return "between " + parts[0] + "–" + parts[1] + " miles";
        }
        if (DISTANCE_TAIL.matcher(s).matches()) {
            return "above " + s.replace("+", "") + " miles";
        }
        if ("0-1".equals(s) || "0–1".equals(s)) return "under 1 mile";
        if (HOUR_BUCKET.matcher(s).matches()) {
            return "around " + formatBucketLabel(s).toLowerCase(Locale.ROOT);
        }
        if ("weekend".equalsIgnoreCase(s)) return "on weekends";
        if ("weekday".equalsIgnoreCase(s)) return "on weekdays";
        return formatBucketLabel(s).toLowerCase(Locale.ROOT);
    }

    public String enrichComparative(ComparativeFinding c) {
        return comparativeSupport(c);
    }

    public String enrichContribution(ContributionFinding c) {
        return contributionSupport(c).isEmpty() ? "" : contributionSupport(c).getFirst();
    }

    public String enrichRanking(RankingFinding r) {
        return rankingSupport(r).isEmpty() ? "" : rankingSupport(r).getFirst();
    }

    // ─── Headlines ───────────────────────────────────────────────────────

    private String contributionHeadline(ContributionFinding c) {
        String leader = formatBucketPhrase(c.topContributor());
        String dim = aliases.resolve(c.dimensionLabel()).toLowerCase(Locale.ROOT);

        if (c.concentrationRatio() >= 55 || c.topContributorSharePct() >= 35) {
            if (dim.contains("distance")) {
                return "Revenue is concentrated in short-distance trips.";
            }
            return String.format(Locale.ROOT,
                    "Revenue is concentrated in %s.", leader);
        }
        if (c.leaderToTailRatio() >= 2.5) {
            return String.format(Locale.ROOT, "Most revenue comes from %s.", leader);
        }
        return String.format(Locale.ROOT,
                "%s is the dominant revenue segment.",
                capitalize(formatBucketLabel(c.topContributor())));
    }

    private String comparativeHeadline(ComparativeFinding c) {
        String leader = formatBucketPhrase(c.delta() >= 0 ? c.entityA() : c.entityB());
        return String.format(Locale.ROOT, "Revenue skews toward %s.", leader);
    }

    private String correlationHeadline(CorrelationFinding c) {
        if (c.executiveSummary() != null && !c.executiveSummary().isBlank()) {
            return c.executiveSummary();
        }
        return String.format(Locale.ROOT, "%s vs %s correlation (r=%.3f).",
                c.sourceVariable(), c.targetVariable(), c.correlationCoefficient());
    }

    private String rankingHeadline(RankingFinding r) {
        if (r.rankedEntities().isEmpty()) return aliases.resolve(r.metricLabel()) + " leaders";
        String leader = formatBucketLabel(r.rankedEntities().getFirst().name());
        return String.format(Locale.ROOT, "%s leads on %s.",
                capitalize(leader), aliases.resolve(r.metricLabel()).toLowerCase(Locale.ROOT));
    }

    private String temporalHeadline(TemporalPatternFinding t) {
        return String.format(Locale.ROOT, "%s shows a %s pattern.",
                aliases.resolve(t.temporalDimension()),
                t.momentum() != null ? t.momentum().toLowerCase(Locale.ROOT) : "mixed");
    }

    private String efficiencyHeadline(EfficiencyFinding e) {
        if (e.entries().isEmpty()) return "Efficiency varies across segments.";
        return String.format(Locale.ROOT, "%s delivers the strongest yield per unit.",
                capitalize(formatBucketLabel(e.entries().getFirst().name())));
    }

    // ─── Support ─────────────────────────────────────────────────────────

    private List<String> contributionSupport(ContributionFinding c) {
        List<String> out = new ArrayList<>();
        if (c.segments().size() >= 2) {
            String leader = formatBucketPhrase(c.topContributor());
            String tail = formatBucketPhrase(c.segments().getLast().name());
            double multiple = c.leaderToTailRatio();
            if (multiple >= 1.5) {
                out.add(String.format(Locale.ROOT,
                        "Trips %s generate ~%s more revenue than trips %s.",
                        leader, metrics.asMultiple(multiple), tail));
            }
        }
        if (c.topContributorSharePct() > 0 && out.size() < 2) {
            out.add(String.format(Locale.ROOT,
                    "%s accounts for %s of total revenue.",
                    capitalize(formatBucketLabel(c.topContributor())),
                    metrics.asSharePct(c.topContributorSharePct())));
        }
        return out.stream().limit(2).toList();
    }

    private String comparativeSupport(ComparativeFinding c) {
        String leader = formatBucketPhrase(c.delta() >= 0 ? c.entityA() : c.entityB());
        String laggard = formatBucketPhrase(c.delta() >= 0 ? c.entityB() : c.entityA());
        double multiple = c.multiple() > 0 ? c.multiple() : (1.0 + Math.abs(c.deltaPct()) / 100.0);
        if (multiple >= 1.5) {
            return String.format(Locale.ROOT,
                    "Trips %s generate ~%s higher revenue than trips %s.",
                    leader, metrics.asMultiple(multiple), laggard);
        }
        return String.format(Locale.ROOT,
                "Revenue is higher %s than %s.", leader, laggard);
    }

    private String correlationSupport(CorrelationFinding c) {
        return String.format(Locale.ROOT,
                "Correlation coefficient r=%.3f across %,d observations (%s %s relationship).",
                c.correlationCoefficient(), c.sampleSize(), c.strength(), c.direction());
    }

    private List<String> rankingSupport(RankingFinding r) {
        if (r.rankedEntities().isEmpty()) return List.of();
        String leader = formatBucketLabel(r.rankedEntities().getFirst().name());
        String tail = formatBucketLabel(r.rankedEntities().getLast().name());
        return List.of(String.format(Locale.ROOT,
                "%s ranks first; spread to %s is %s.",
                capitalize(leader), tail.toLowerCase(Locale.ROOT),
                metrics.asMultiple(r.leaderToTailMultiple())));
    }

    private String temporalSupport(TemporalPatternFinding t) {
        return String.format(Locale.ROOT,
                "Peak at %s (%s).",
                formatBucketLabel(t.peakPeriod()),
                metrics.formatValue(t.peakValue(), t.temporalDimension()));
    }

    private String efficiencySupport(EfficiencyFinding e) {
        if (e.entries().isEmpty()) return "";
        return String.format(Locale.ROOT,
                "%s leads with %s spread across segments.",
                capitalize(formatBucketLabel(e.entries().getFirst().name())),
                metrics.asMultiple(e.efficiencySpread()));
    }

    // ─── Evidence ────────────────────────────────────────────────────────

    private String contributionEvidence(ContributionFinding c) {
        return String.format(Locale.ROOT,
                "Top segment: %s (%s share); top 3 hold %s.",
                formatBucketLabel(c.topContributor()),
                metrics.asSharePct(c.topContributorSharePct()),
                metrics.asSharePct(c.concentrationRatio()));
    }

    private String comparativeEvidence(ComparativeFinding c) {
        double multiple = c.multiple() > 0 ? c.multiple() : (1.0 + Math.abs(c.deltaPct()) / 100.0);
        return String.format(Locale.ROOT,
                "Leader segment is %s higher; gap is %s.",
                metrics.asMultiple(multiple),
                metrics.formatValue(Math.abs(c.delta()), c.metricLabel()));
    }

    private String correlationEvidence(CorrelationFinding c) {
        return String.format(Locale.ROOT,
                "r=%.3f | n=%,d | %s %s correlation.",
                c.correlationCoefficient(), c.sampleSize(), c.strength(), c.direction());
    }

    private String rankingEvidence(RankingFinding r) {
        if (r.rankedEntities().isEmpty()) return "";
        return String.format(Locale.ROOT,
                "Leader: %s (%s); tail spread %s.",
                formatBucketLabel(r.rankedEntities().getFirst().name()),
                metrics.formatValue(r.leaderValue(), r.metricLabel()),
                metrics.asMultiple(r.leaderToTailMultiple()));
    }

    private String temporalEvidence(TemporalPatternFinding t) {
        return String.format(Locale.ROOT,
                "Momentum: %s across %d periods.",
                t.momentum() != null ? t.momentum().toLowerCase(Locale.ROOT) : "flat",
                t.periods() != null ? t.periods().size() : 0);
    }

    private String efficiencyEvidence(EfficiencyFinding e) {
        if (e.entries().isEmpty()) return "";
        return String.format(Locale.ROOT,
                "Best yield: %s (%s per unit).",
                formatBucketLabel(e.entries().getFirst().name()),
                metrics.compactNumber(e.entries().getFirst().efficiencyRatio()));
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
