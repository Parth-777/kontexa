package com.example.BACKEND.catalogue.decision.execution;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates specific, quantified, non-obvious findings from computed entity data.
 *
 * This is the most important component in the execution layer.
 * It converts computed numbers into precise analytical statements that the
 * LLM should treat as pre-computed OBSERVATIONS, not infer itself.
 *
 * Quality standard: every finding must be:
 *   1. Specific — names the entity and the metric
 *   2. Quantified — includes the actual number (not "higher" but "2.4x higher")
 *   3. Comparative — relative to peer average, not absolute only
 *   4. Non-obvious — something a simple aggregate query would NOT reveal
 *
 * Examples of correct findings:
 *   "Zone 132 → JFK corridor generates $8.40/minute vs. network average of $3.50/minute — 2.4x efficiency premium."
 *   "Top 3 zones account for 47% of total fare revenue despite representing 11% of trip volume."
 *   "Airport trips generate 2.1x higher fare/trip than non-airport trips while representing only 7.9% of volume."
 *
 * Anti-patterns (never generated):
 *   "Revenue may be influenced by trip patterns."
 *   "Further investigation is needed."
 *   "Data suggests longer trips generate more revenue."
 */
@Component
public class StructuralFindingGenerator {

    public List<StructuralFinding> generate(
            List<ConstructedEntity> entities,
            List<RankedEntity>      primaryRanking,
            List<RankedEntity>      efficiencyRanking,
            StatisticalContext      stats,
            AnalyticalIntentType    intentType
    ) {
        List<StructuralFinding> findings = new ArrayList<>();

        // 1. Top performer finding (most universally applicable)
        topPerformerFinding(primaryRanking, stats).ifPresent(findings::add);

        // 2. Efficiency leader finding
        efficiencyLeaderFinding(efficiencyRanking, stats).ifPresent(findings::add);

        // 3. Efficiency vs. scale divergence (non-obvious)
        scaleDivergenceFinding(primaryRanking, efficiencyRanking).ifPresent(findings::add);

        // 4. Concentration finding
        concentrationFinding(primaryRanking, stats).ifPresent(findings::add);

        // 5. Underperformer gap
        underperformerFinding(primaryRanking, stats).ifPresent(findings::add);

        // 6. Intent-specific findings
        findings.addAll(intentSpecificFindings(entities, primaryRanking, efficiencyRanking, intentType, stats));

        return findings.stream()
                .filter(f -> f.findingText() != null && !f.findingText().isBlank())
                .limit(7)
                .collect(Collectors.toList());
    }

    // ─── finding generators ──────────────────────────────────────────────

    private Optional<StructuralFinding> topPerformerFinding(
            List<RankedEntity> ranking, StatisticalContext stats) {
        if (ranking.isEmpty() || stats.peerAveragePrimaryMetric() == 0) return Optional.empty();

        RankedEntity top = ranking.get(0);
        if (top.multiplierVsAverage() < 1.3) return Optional.empty(); // Not worth flagging

        return Optional.of(new StructuralFinding(
                String.format("#1 performer [%s] generates %.1fx the network average %s (%.2f vs avg %.2f). " +
                              "Positioned at the %.0fth percentile.",
                        top.entityKey(), top.multiplierVsAverage(), top.rankingDimension(),
                        top.value(), top.peerAverage(), top.percentileRank()),
                top.multiplierVsAverage(),
                "top-performer ranking",
                top.multiplierVsAverage() >= 2.0
        ));
    }

    private Optional<StructuralFinding> efficiencyLeaderFinding(
            List<RankedEntity> effRanking, StatisticalContext stats) {
        if (effRanking.isEmpty()) return Optional.empty();

        RankedEntity top = effRanking.get(0);
        if (top.multiplierVsAverage() < 1.4) return Optional.empty();

        return Optional.of(new StructuralFinding(
                String.format("Efficiency leader [%s]: %.2f %s vs. peer average %.2f — %.1fx efficiency premium.",
                        top.entityKey(), top.value(), top.rankingDimension(),
                        top.peerAverage(), top.multiplierVsAverage()),
                top.multiplierVsAverage(),
                "efficiency ranking (" + top.rankingDimension() + ")",
                top.multiplierVsAverage() >= 1.8
        ));
    }

    private Optional<StructuralFinding> scaleDivergenceFinding(
            List<RankedEntity> primary, List<RankedEntity> efficiency) {
        if (primary.size() < 3 || efficiency.size() < 3) return Optional.empty();

        // Find entities top-3 in primary but NOT top-3 in efficiency (scale != quality)
        Set<String> topPrimary   = primary.subList(0, Math.min(3, primary.size())).stream()
                .map(RankedEntity::entityKey).collect(Collectors.toSet());
        Set<String> topEfficiency = efficiency.subList(0, Math.min(3, efficiency.size())).stream()
                .map(RankedEntity::entityKey).collect(Collectors.toSet());

        // Find efficiency leaders not in top primary (hidden efficiency gems)
        List<String> hiddenGems = topEfficiency.stream()
                .filter(e -> !topPrimary.contains(e)).collect(Collectors.toList());

        if (hiddenGems.isEmpty()) return Optional.empty();

        String gem = hiddenGems.get(0);
        RankedEntity effEntry = efficiency.stream()
                .filter(r -> r.entityKey().equals(gem)).findFirst().orElse(null);
        if (effEntry == null) return Optional.empty();

        return Optional.of(new StructuralFinding(
                String.format("[%s] ranks in the top-3 for efficiency (%s = %.2f) but does NOT appear in " +
                              "the top-3 by volume — a high-efficiency entity underrepresented in absolute rankings.",
                        gem, effEntry.rankingDimension(), effEntry.value()),
                effEntry.multiplierVsAverage(),
                "scale vs efficiency divergence analysis",
                true
        ));
    }

    private Optional<StructuralFinding> concentrationFinding(
            List<RankedEntity> ranking, StatisticalContext stats) {
        if (ranking.size() < 5 || stats.totalEntitiesConstructed() == 0) return Optional.empty();

        // Compute share held by top 3 entities
        double top3Sum  = ranking.stream().limit(3).mapToDouble(RankedEntity::value).sum();
        double totalSum = ranking.stream().mapToDouble(RankedEntity::value).sum();
        if (totalSum == 0) return Optional.empty();

        double top3Share = 100.0 * top3Sum / totalSum;
        if (top3Share < 35) return Optional.empty(); // Not concentrated enough to be interesting

        int top3Count = 3;
        int total     = ranking.size();
        double volShare = 100.0 * top3Count / total;

        return Optional.of(new StructuralFinding(
                String.format("Top %d entities account for %.1f%% of total %s " +
                              "despite representing only %.1f%% of entity count — concentration risk.",
                        top3Count, top3Share,
                        ranking.get(0).rankingDimension().replace("_", " "),
                        volShare),
                top3Share,
                "concentration analysis (top-3 share)",
                top3Share >= 50
        ));
    }

    private Optional<StructuralFinding> underperformerFinding(
            List<RankedEntity> ranking, StatisticalContext stats) {
        if (ranking.size() < 5) return Optional.empty();

        RankedEntity bottom = ranking.get(ranking.size() - 1);
        RankedEntity top    = ranking.get(0);
        if (top.value() == 0) return Optional.empty();

        double gapMultiplier = top.value() / Math.max(0.01, bottom.value());
        if (gapMultiplier < 3) return Optional.empty();

        return Optional.of(new StructuralFinding(
                String.format("Performance gap: top entity [%s] generates %.1fx more %s than " +
                              "the bottom performer [%s] — substantial polarisation across entities.",
                        top.entityKey(), gapMultiplier,
                        top.rankingDimension().replace("_", " "),
                        bottom.entityKey()),
                gapMultiplier,
                "top-to-bottom performance gap",
                gapMultiplier >= 5
        ));
    }

    private List<StructuralFinding> intentSpecificFindings(
            List<ConstructedEntity> entities,
            List<RankedEntity>      primary,
            List<RankedEntity>      efficiency,
            AnalyticalIntentType    intentType,
            StatisticalContext      stats
    ) {
        List<StructuralFinding> extra = new ArrayList<>();

        switch (intentType) {
            case CONTRIBUTION -> {
                // Highlight entities above proportional share
                if (!primary.isEmpty()) {
                    long aboveAvg = primary.stream()
                            .filter(r -> r.multiplierVsAverage() > 1.2).count();
                    if (aboveAvg > 0 && aboveAvg < primary.size()) {
                        extra.add(new StructuralFinding(
                                String.format("%d of %d entities contribute above their proportional share " +
                                              "(>1.2x average) — unequal contribution distribution.",
                                        aboveAvg, primary.size()),
                                (double) aboveAvg / primary.size(),
                                "above-proportional-share count",
                                true
                        ));
                    }
                }
            }
            case STRATEGIC_PRIORITIZATION -> {
                // Find entities high on efficiency but low volume (strategic opportunity)
                if (!efficiency.isEmpty() && !primary.isEmpty()) {
                    Map<String, Integer> effRanks = new HashMap<>();
                    for (int i = 0; i < efficiency.size(); i++) {
                        effRanks.put(efficiency.get(i).entityKey(), i + 1);
                    }
                    for (int i = primary.size() / 2; i < Math.min(primary.size(), primary.size() / 2 + 3); i++) {
                        RankedEntity lowVol = primary.get(i);
                        Integer effRank = effRanks.get(lowVol.entityKey());
                        if (effRank != null && effRank <= primary.size() / 4) {
                            extra.add(new StructuralFinding(
                                    String.format("[%s] ranks #%d in volume but #%d in efficiency — " +
                                                  "high-efficiency, under-scaled entity with growth potential.",
                                            lowVol.entityKey(), i + 1, effRank),
                                    lowVol.multiplierVsAverage(),
                                    "efficiency vs volume rank divergence",
                                    true
                            ));
                            break;
                        }
                    }
                }
            }
            default -> { /* no additional intent-specific findings */ }
        }

        return extra;
    }
}
