package com.example.BACKEND.catalogue.decision.playbooks;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Playbook: Growth Momentum Analysis
 *
 * Purpose: Identify acceleration and expansion opportunities by analysing
 * growth rate, consistency, segment acceleration, and cohort quality.
 *
 * Output: Emerging opportunities, high-momentum segments,
 * sustainable vs temporary growth signals, expansion recommendations.
 */
@Component
public class GrowthMomentumPlaybook implements Playbook {

    @Override
    public String playbookKey() { return "GROWTH_MOMENTUM"; }

    @Override
    public String displayName() { return "Growth Momentum Analysis"; }

    @Override
    public String analyticalPurpose() {
        return "Identify which segments and entities are accelerating, and whether growth "
                + "is structural (sustainable) or transient — to prioritise expansion investment.";
    }

    @Override
    public boolean supports(String objectiveKey) {
        return "GROWTH_MOMENTUM".equals(objectiveKey);
    }

    @Override
    public Map<String, Double> rankingWeightOverrides() {
        return Map.of(
                "volatility",           0.28,  // high positive volatility = strong growth signal
                "comparative_depth",    0.22,  // trend context is essential for momentum
                "investigation_impact", 0.18,  // which segments are accelerating?
                "confidence",           0.15,
                "data_breadth",         0.10,  // multi-signal growth is more credible
                "breadth_of_impact",    0.07   // broad-based growth > concentrated
        );
    }

    @Override
    public String synthesisSystemExtension() {
        return """
                PLAYBOOK: Growth Momentum Analysis

                ANALYTICAL LENS:
                - Lead with the single highest-momentum signal — which entity/segment is accelerating fastest.
                - Distinguish STRUCTURAL growth (consistent 3+ periods) from TRANSIENT (single-period spike).
                - Identify the GROWTH LEADER and the FASTEST ACCELERATOR — they may be different entities.
                - Flag EMERGING segments: small absolute size but rapid growth rate — the future opportunities.
                - State whether growth is BROAD-BASED (multiple segments) or CONCENTRATED (1-2 drivers).
                - Highlight segments showing DECELERATION — leading indicators of future problems.
                - Recommend where to invest based on sustainability and momentum evidence.

                OUTPUT PRIORITIES (in order):
                1. Highest-momentum signal with growth rate and consistency
                2. Structural vs transient classification
                3. Emerging opportunities (high growth, smaller base)
                4. Segments at risk of deceleration
                5. Investment allocation recommendation
                """;
    }

    @Override
    public List<String> investigationPriorities() {
        return List.of("growth_acceleration", "consistency_periods", "emerging_segments",
                       "deceleration_signals", "geographic_expansion");
    }

    @Override
    public List<String> businessContextHints() {
        return List.of(
                "Momentum compounds — early identification of accelerating segments is high-value.",
                "Single-period growth spikes without prior trend are likely seasonal or one-off.",
                "The fastest-growing small segment today is often the top revenue driver in 12 months.",
                "Deceleration in a dominant segment is a leading indicator of revenue risk."
        );
    }
}
