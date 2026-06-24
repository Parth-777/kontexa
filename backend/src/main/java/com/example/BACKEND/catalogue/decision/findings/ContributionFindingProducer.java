package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ContributionFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ContributionFinding.Segment;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.grounding.SemanticRelationshipValidator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Produces {@link ContributionFinding}s from a {@link MaterializedGrouping}.
 *
 * The grouping already has pre-computed sharePct, tier, and rank for each entry.
 * This producer:
 *   1. Converts entries to typed Segment records with contribution tier labels
 *   2. Computes aggregate statistics: top-3 concentration, Gini, leader/tail ratio
 *   3. Generates a one-line executive summary stating the key concentration fact
 */
@Component
public class ContributionFindingProducer {

    private final PresentationLabelResolver labels;
    private final SemanticRelationshipValidator validator;

    public ContributionFindingProducer(
            PresentationLabelResolver labels,
            SemanticRelationshipValidator validator
    ) {
        this.labels = labels;
        this.validator = validator;
    }

    public Optional<ContributionFinding> produce(MaterializedGrouping grouping, String metricLabel) {
        if (grouping == null || !grouping.hasData()) return Optional.empty();

        List<MaterializedGroupEntry> entries = grouping.rankedEntries();
        if (entries.isEmpty()) return Optional.empty();

        String dimensionLabel = labels.resolveDimension(grouping.spec().displayLabel());
        String resolvedMetric = labels.resolveMetric(metricLabel != null ? metricLabel : "Revenue");

        if (!validator.isMetricDimensionCompatible(resolvedMetric, dimensionLabel)) {
            return Optional.empty();
        }

        List<Segment> segments = entries.stream()
                .map(e -> new Segment(
                        labels.resolveSegment(e.entityKey()),
                        e.totalValue(),
                        e.sharePct(),
                        e.rank(),
                        contributionTier(e.sharePct())
                ))
                .collect(Collectors.toList());

        Segment top = segments.get(0);

        // Concentration: top-3 combined share
        double top3Share = segments.stream()
                .limit(3).mapToDouble(Segment::sharePct).sum();

        double leaderToTailRatio = segments.get(segments.size() - 1).value() > 0
                ? top.value() / segments.get(segments.size() - 1).value()
                : top.value();

        ContributionFinding draft = new ContributionFinding(
                dimensionLabel,
                segments,
                top.name(),
                top.sharePct(),
                top3Share,
                grouping.giniConcentration(),
                leaderToTailRatio,
                "",
                resolvedMetric
        );
        String summary = validator.correctContributionSummary(draft);

        return Optional.of(new ContributionFinding(
                dimensionLabel,
                segments,
                top.name(),
                top.sharePct(),
                top3Share,
                grouping.giniConcentration(),
                leaderToTailRatio,
                summary,
                resolvedMetric
        ));
    }

    private String contributionTier(double sharePct) {
        if (sharePct >= 30) return "DOMINANT";
        if (sharePct >= 10) return "SIGNIFICANT";
        return "MINOR";
    }

}
