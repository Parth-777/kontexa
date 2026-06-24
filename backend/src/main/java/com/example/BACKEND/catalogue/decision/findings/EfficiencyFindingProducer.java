package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.EfficiencyFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.EfficiencyFinding.EfficiencyEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Produces {@link EfficiencyFinding}s from a {@link MaterializedGrouping}
 * whose entries have a non-zero {@code efficiencyRatio}.
 *
 * The efficiency ratio is pre-computed by {@link
 * com.example.BACKEND.catalogue.decision.execution.materialization.GroupByExecutor}
 * as totalValue / volumeCount.
 *
 * This producer adds:
 *   - deviation from mean (relative to group average)
 *   - tier classification: ELITE / HIGH / AVERAGE / BELOW / POOR
 *   - spread metric: best / worst efficiency ratio
 */
@Component
public class EfficiencyFindingProducer {

    public Optional<EfficiencyFinding> produce(MaterializedGrouping grouping,
                                               String numeratorLabel,
                                               String denominatorLabel) {
        if (grouping == null || !grouping.hasData()) return Optional.empty();

        List<MaterializedGroupEntry> withRatio = grouping.rankedEntries().stream()
                .filter(e -> e.efficiencyRatio() > 0 && e.volumeCount() > 0)
                .collect(Collectors.toList());

        if (withRatio.size() < 2) return Optional.empty();

        double avgEfficiency = withRatio.stream()
                .mapToDouble(MaterializedGroupEntry::efficiencyRatio).average().orElse(0);

        double bestEfficiency  = withRatio.stream()
                .mapToDouble(MaterializedGroupEntry::efficiencyRatio).max().orElse(0);
        double worstEfficiency = withRatio.stream()
                .mapToDouble(MaterializedGroupEntry::efficiencyRatio).min().orElse(0);
        double spread = worstEfficiency > 0 ? bestEfficiency / worstEfficiency : bestEfficiency;

        List<EfficiencyEntry> entries = withRatio.stream()
                .map(e -> toEntry(e, avgEfficiency))
                .sorted((a, b) -> Double.compare(b.efficiencyRatio(), a.efficiencyRatio()))
                .collect(Collectors.toList());

        String best = entries.get(0).name();
        String summary = String.format(
                "%s efficiency by %s/%s: '%s' leads at %.3f " +
                "(%.1fx the group average, %.1fx the lowest). Spread across %d groups = %.1fx.",
                grouping.spec().displayLabel(),
                numeratorLabel, denominatorLabel,
                best, bestEfficiency,
                avgEfficiency > 0 ? bestEfficiency / avgEfficiency : 0,
                spread, entries.size(), spread);

        return Optional.of(new EfficiencyFinding(
                grouping.spec().displayLabel(),
                numeratorLabel,
                denominatorLabel,
                entries,
                bestEfficiency,
                worstEfficiency,
                avgEfficiency,
                spread,
                summary
        ));
    }

    private EfficiencyEntry toEntry(MaterializedGroupEntry e, double avgEfficiency) {
        double deviationFromMean = avgEfficiency > 0
                ? (e.efficiencyRatio() - avgEfficiency) / avgEfficiency : 0;

        return new EfficiencyEntry(
                e.entityKey(),
                e.totalValue(),
                e.volumeCount(),
                e.efficiencyRatio(),
                deviationFromMean,
                efficiencyTier(deviationFromMean)
        );
    }

    private String efficiencyTier(double deviationFromMean) {
        if (deviationFromMean >  0.5)  return "ELITE";
        if (deviationFromMean >  0.15) return "HIGH";
        if (deviationFromMean > -0.15) return "AVERAGE";
        if (deviationFromMean > -0.4)  return "BELOW";
        return "POOR";
    }
}
