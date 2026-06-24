package com.example.BACKEND.catalogue.decision.playbooks;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Playbook: Strategic Value Ranking
 *
 * Purpose: Identify which entities are highest-value using weighted
 * business scoring across revenue, efficiency, growth, and risk dimensions.
 *
 * Output: Ranked entities with strategic scores, strengths/weaknesses,
 * concentration risks, and opportunity areas.
 */
@Component
public class StrategicValueRankingPlaybook implements Playbook {

    @Override
    public String playbookKey() { return "STRATEGIC_VALUE_RANKING"; }

    @Override
    public String displayName() { return "Strategic Value Ranking"; }

    @Override
    public String analyticalPurpose() {
        return "Identify highest-value entities using weighted strategic scoring across "
                + "revenue contribution, monetization efficiency, growth stability, and concentration risk.";
    }

    @Override
    public boolean supports(String objectiveKey) {
        return "STRATEGIC_VALUE_RANKING".equals(objectiveKey)
                || "RANKING_ANALYSIS".equals(objectiveKey);
    }

    @Override
    public Map<String, Double> rankingWeightOverrides() {
        return Map.of(
                "comparative_depth",    0.25,  // peer comparison is the backbone of ranking
                "data_breadth",         0.20,  // holistic signal coverage = better strategic score
                "volatility",           0.18,  // stability matters as much as magnitude
                "confidence",           0.15,
                "downside_risk",        0.10,  // concentration risk is a strategic red flag
                "breadth_of_impact",    0.07,
                "anomaly_severity",     0.05
        );
    }

    @Override
    public String synthesisSystemExtension() {
        return """
                PLAYBOOK: Strategic Value Ranking

                ANALYTICAL LENS:
                - Produce a ranked list of entities with an explicit #1, #2, #3 ordering.
                - For each top entity, state WHY it ranks highest — not just that it does.
                - Identify the STRATEGIC GAP between leader and the rest.
                - Flag CONCENTRATION RISK if ≤ 3 entities account for > 50% of total value.
                - Identify one entity with HIDDEN POTENTIAL — high growth, lower current absolute value.
                - Distinguish between entities with STRUCTURAL advantages vs TRANSIENT performance.

                OUTPUT PRIORITIES (in order):
                1. Who leads and why (concrete metrics)
                2. The gap and what it means strategically
                3. The most actionable opportunity or risk
                4. Concentration risk if present
                """;
    }

    @Override
    public List<String> investigationPriorities() {
        return List.of("revenue_concentration", "growth_stability", "peer_gap", "efficiency_ratio");
    }

    @Override
    public List<String> businessContextHints() {
        return List.of(
                "Strategic ranking reveals where to invest, protect, or divest.",
                "Concentration above 60% in top 3 entities is a portfolio risk.",
                "Leader-to-second gap > 30% suggests winner-take-most dynamics.",
                "Entities with high growth but low current rank are expansion candidates."
        );
    }
}
