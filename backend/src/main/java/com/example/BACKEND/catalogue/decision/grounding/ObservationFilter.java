package com.example.BACKEND.catalogue.decision.grounding;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EvidenceObject;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes low-semantic-value observations before presentation.
 */
@Component
public class ObservationFilter {

    private static final Set<String> MEANINGLESS_KEYS = Set.of(
            "total", "count", "row_count", "sum", "value", "metric_value",
            "group_key", "entity_key", "share_pct", "efficiency_ratio"
    );

    private final PresentationLabelResolver labels;
    private final SemanticRelationshipValidator validator;

    public ObservationFilter(PresentationLabelResolver labels, SemanticRelationshipValidator validator) {
        this.labels = labels;
        this.validator = validator;
    }

    public List<AnalyticalFinding> filterFindings(List<AnalyticalFinding> findings) {
        if (findings == null) return List.of();

        List<AnalyticalFinding> kept = new ArrayList<>();
        Set<String> seenSummaries = new HashSet<>();

        for (AnalyticalFinding f : findings) {
            SemanticRelationshipValidator.ValidationResult vr = validator.validateFinding(f);
            if (!vr.valid()) continue;

            String summary = vr.correctedSummary();
            if (summary == null || summary.isBlank()) continue;
            if (!seenSummaries.add(summary.toLowerCase(Locale.ROOT))) continue;

            kept.add(rewriteFinding(f, vr.correctedSummary()));
        }
        return kept;
    }

    public List<EvidenceObject> filterEvidence(List<EvidenceObject> evidence) {
        if (evidence == null) return List.of();

        List<EvidenceObject> kept = new ArrayList<>();
        Set<String> seenValues = new HashSet<>();

        for (EvidenceObject ev : evidence) {
            Map<String, Object> filtered = filterMetrics(ev.metrics());
            if (filtered.isEmpty()) continue;

            String fingerprint = filtered.toString();
            if (!seenValues.add(fingerprint)) continue;

            kept.add(new EvidenceObject(
                    ev.evidenceId(), ev.entityRef(), filtered,
                    ev.comparisons(), ev.signals(), ev.comparativeContexts(),
                    ev.investigationTree(), ev.confidence(), ev.lineageRefs()
            ));
        }
        return kept;
    }

    public Map<String, Object> filterMetrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) return Map.of();

        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> seenKeys = new HashSet<>();

        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            if (labels.isInternalKey(key)) continue;
            if (MEANINGLESS_KEYS.contains(key.toLowerCase(Locale.ROOT)) && !labels.isLikelyMetric(key)) {
                continue;
            }
            if (!seenKeys.add(key.toLowerCase(Locale.ROOT))) continue;

            Object val = e.getValue();
            if (val == null || String.valueOf(val).isBlank()) continue;
            if (isUnsupportedCorrelation(key)) continue;

            out.put(labels.resolveMetric(key), val);
        }
        return out;
    }

    private boolean isUnsupportedCorrelation(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("corr") || lower.contains("correlation") || lower.contains("p_value");
    }

    private AnalyticalFinding rewriteFinding(AnalyticalFinding f, String correctedSummary) {
        return switch (f) {
            case ContributionFinding c -> new ContributionFinding(
                    labels.resolveDimension(c.dimensionLabel()),
                    rewriteSegments(c.segments()),
                    labels.resolveSegment(c.topContributor()),
                    c.topContributorSharePct(),
                    c.concentrationRatio(),
                    c.giniCoefficient(),
                    c.leaderToTailRatio(),
                    correctedSummary,
                    labels.resolveMetric(c.metricLabel())
            );
            case RankingFinding r -> new RankingFinding(
                    labels.resolveMetric(r.metricLabel()),
                    labels.resolveDimension(r.groupingLabel()),
                    rewriteRanked(r.rankedEntities()),
                    r.leaderValue(), r.medianValue(), r.tailValue(),
                    r.leaderToMedianMultiple(), r.leaderToTailMultiple(),
                    r.peerAverage(),
                    validator.correctRankingSummary(
                            new RankingFinding(
                                    labels.resolveMetric(r.metricLabel()),
                                    labels.resolveDimension(r.groupingLabel()),
                                    rewriteRanked(r.rankedEntities()),
                                    r.leaderValue(), r.medianValue(), r.tailValue(),
                                    r.leaderToMedianMultiple(), r.leaderToTailMultiple(),
                                    r.peerAverage(), correctedSummary))
            );
            case EfficiencyFinding e -> new EfficiencyFinding(
                    labels.resolveDimension(e.groupingLabel()),
                    labels.resolveMetric(e.numeratorLabel()),
                    labels.resolveMetric(e.denominatorLabel()),
                    e.entries(), e.bestEfficiency(), e.worstEfficiency(),
                    e.averageEfficiency(), e.efficiencySpread(), correctedSummary
            );
            case TemporalPatternFinding t -> new TemporalPatternFinding(
                    labels.resolveDimension(t.temporalDimension()),
                    t.periods(), t.peakPeriod(), t.peakValue(),
                    t.troughPeriod(), t.troughValue(), t.volatility(),
                    t.momentum(), t.inflectionPoints(), correctedSummary
            );
            case ComparativeFinding c -> {
                ComparativeFinding draft = new ComparativeFinding(
                        labels.resolveSegment(c.entityA()),
                        labels.resolveSegment(c.entityB()),
                        c.valueA(), c.valueB(), c.delta(), c.deltaPct(),
                        c.direction(), c.multiple(),
                        labels.resolveMetric(c.metricLabel()), correctedSummary);
                yield new ComparativeFinding(
                        draft.entityA(), draft.entityB(),
                        draft.valueA(), draft.valueB(), draft.delta(), draft.deltaPct(),
                        draft.direction(), draft.multiple(),
                        draft.metricLabel(), validator.correctComparativeSummary(draft));
            }
            case CorrelationFinding c -> new CorrelationFinding(
                    c.sourceVariable(), c.targetVariable(),
                    c.correlationCoefficient(), c.sampleSize(),
                    c.strength(), c.direction(),
                    c.interpretation(),
                    correctedSummary != null && !correctedSummary.isBlank()
                            ? correctedSummary : c.executiveSummary());
        };
    }

    private List<ContributionFinding.Segment> rewriteSegments(List<ContributionFinding.Segment> segments) {
        return segments.stream()
                .map(s -> new ContributionFinding.Segment(
                        labels.resolveSegment(s.name()),
                        s.value(), s.sharePct(), s.rank(), s.tier()))
                .toList();
    }

    private List<RankingFinding.RankedEntity> rewriteRanked(List<RankingFinding.RankedEntity> entities) {
        return entities.stream()
                .map(e -> new RankingFinding.RankedEntity(
                        labels.resolveSegment(e.name()),
                        e.value(), e.rank(), e.percentileRank(),
                        e.multiplierVsAvg(), e.gapToLeader(), e.gapToLeaderPct(), e.tier()))
                .toList();
    }
}
