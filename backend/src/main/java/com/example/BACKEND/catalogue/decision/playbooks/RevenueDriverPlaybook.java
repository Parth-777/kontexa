package com.example.BACKEND.catalogue.decision.playbooks;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Playbook: Revenue Driver Analysis
 *
 * Purpose: Explain what is driving or hurting revenue across geography,
 * segment, channel, cohort, time period, and product/service mix.
 *
 * Output: Growth contributors vs detractors, momentum shifts,
 * concentration risks, and probable business causes.
 */
@Component
public class RevenueDriverPlaybook implements Playbook {

    @Override
    public String playbookKey() { return "REVENUE_DRIVER_ANALYSIS"; }

    @Override
    public String displayName() { return "Revenue Driver Analysis"; }

    @Override
    public String analyticalPurpose() {
        return "Decompose revenue performance into contributors and detractors "
                + "across segments, time periods, and dimensions to identify momentum shifts.";
    }

    @Override
    public boolean supports(String objectiveKey) {
        return "REVENUE_DRIVER_ANALYSIS".equals(objectiveKey)
                || "TREND_ANALYSIS".equals(objectiveKey)
                || "COMPARATIVE_ANALYSIS".equals(objectiveKey);
    }

    @Override
    public Map<String, Double> rankingWeightOverrides() {
        return Map.of(
                "downside_risk",        0.25,  // revenue decline demands immediate attention
                "investigation_impact", 0.20,  // segment-level root cause is the core output
                "volatility",           0.20,  // magnitude of revenue movement
                "comparative_depth",    0.15,  // period-over-period and vs-baseline framing
                "confidence",           0.12,
                "breadth_of_impact",    0.08   // systemic vs isolated revenue movement
        );
    }

    @Override
    public String synthesisSystemExtension() {
        return """
                PLAYBOOK: Revenue Driver Analysis

                ANALYTICAL LENS:
                - Open with the NET revenue change and direction vs prior period. Lead with the number.
                - Decompose into CONTRIBUTORS (what grew) and DETRACTORS (what declined).
                - Identify the DOMINANT DRIVER — the single segment or factor responsible for most of the change.
                - State whether the movement is CONCENTRATED (1-2 segments) or SYSTEMIC (broad-based).
                - Infer the probable BUSINESS CAUSE — pricing, volume, mix shift, churn, seasonality.
                - Flag any momentum shift: is the trend accelerating, decelerating, or reversing?
                - Recommend the highest-leverage intervention point.

                OUTPUT PRIORITIES (in order):
                1. Net change with dominant driver identified
                2. Contributors vs detractors breakdown
                3. Whether this is concentrated or systemic
                4. Probable business cause with commercial rationale
                5. Momentum trajectory and urgency
                """;
    }

    @Override
    public List<String> investigationPriorities() {
        return List.of("segment_contribution", "period_momentum", "geographic_breakdown",
                       "channel_mix", "cohort_performance");
    }

    @Override
    public List<String> businessContextHints() {
        return List.of(
                "Revenue driver analysis separates structural trends from noise.",
                "A single-segment decline driving >50% of total drop is a targeted problem, not a market problem.",
                "Accelerating decline over 3+ periods signals structural risk, not a one-off.",
                "Mix shift (same volume, lower revenue) often indicates pricing pressure."
        );
    }
}
