package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.RankingFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.RankingFinding.RankedEntity;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.grounding.SemanticRelationshipValidator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

@Component
public class RankingFindingProducer {

    private final PresentationLabelResolver labels;
    private final SemanticRelationshipValidator validator;

    public RankingFindingProducer(
            PresentationLabelResolver labels,
            SemanticRelationshipValidator validator
    ) {
        this.labels = labels;
        this.validator = validator;
    }

    public Optional<RankingFinding> produce(MaterializedGrouping grouping, String metricLabel) {
        if (grouping == null || !grouping.hasData()) return Optional.empty();

        List<MaterializedGroupEntry> entries = grouping.rankedEntries();
        if (entries.size() < 2) return Optional.empty();

        String resolvedMetric = labels.resolveMetric(metricLabel);
        String resolvedDimension = labels.resolveDimension(grouping.spec().displayLabel());

        if (!validator.isMetricDimensionCompatible(resolvedMetric, resolvedDimension)) {
            return Optional.empty();
        }

        MaterializedGroupEntry leader = entries.get(0);
        MaterializedGroupEntry tail   = entries.get(entries.size() - 1);

        double peerAverage = entries.stream()
                .mapToDouble(MaterializedGroupEntry::totalValue).average().orElse(0);

        OptionalDouble medianOpt = entries.stream()
                .mapToDouble(MaterializedGroupEntry::totalValue)
                .sorted()
                .skip(entries.size() / 2)
                .findFirst();
        double median = medianOpt.orElse(peerAverage);

        double leaderToMedianMultiple = median > 0 ? leader.totalValue() / median : 0;
        double leaderToTailMultiple   = tail.totalValue() > 0
                ? leader.totalValue() / tail.totalValue() : leader.totalValue();

        List<RankedEntity> rankedEntities = entries.stream()
                .map(e -> toRankedEntity(e, leader.totalValue(), peerAverage))
                .collect(Collectors.toList());

        RankingFinding draft = new RankingFinding(
                resolvedMetric,
                resolvedDimension,
                rankedEntities,
                leader.totalValue(),
                median,
                tail.totalValue(),
                leaderToMedianMultiple,
                leaderToTailMultiple,
                peerAverage,
                ""
        );

        return Optional.of(new RankingFinding(
                resolvedMetric,
                resolvedDimension,
                rankedEntities,
                leader.totalValue(),
                median,
                tail.totalValue(),
                leaderToMedianMultiple,
                leaderToTailMultiple,
                peerAverage,
                validator.correctRankingSummary(draft)
        ));
    }

    private RankedEntity toRankedEntity(MaterializedGroupEntry e,
                                        double leaderValue, double peerAvg) {
        double gapToLeader    = leaderValue - e.totalValue();
        double gapToLeaderPct = leaderValue > 0
                ? (gapToLeader / leaderValue) * 100 : 0;

        return new RankedEntity(
                labels.resolveSegment(e.entityKey()),
                e.totalValue(),
                e.rank(),
                e.percentileRank(),
                e.multiplierVsAvg(),
                gapToLeader,
                gapToLeaderPct,
                e.tier()
        );
    }
}
