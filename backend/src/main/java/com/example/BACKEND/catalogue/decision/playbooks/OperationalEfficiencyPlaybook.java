package com.example.BACKEND.catalogue.decision.playbooks;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Playbook: Operational Efficiency Analysis
 *
 * Purpose: Detect inefficiencies, bottlenecks, and unit-economics deterioration
 * across throughput, utilization, latency, idle time, and cost efficiency.
 *
 * Output: Bottlenecks, high-cost segments, optimization opportunities,
 * and operational risk flags.
 */
@Component
public class OperationalEfficiencyPlaybook implements Playbook {

    @Override
    public String playbookKey() { return "OPERATIONAL_EFFICIENCY"; }

    @Override
    public String displayName() { return "Operational Efficiency Analysis"; }

    @Override
    public String analyticalPurpose() {
        return "Detect operational bottlenecks, unit-economics degradation, and "
                + "efficiency gaps by analysing throughput, utilization, and cost signals.";
    }

    @Override
    public boolean supports(String objectiveKey) {
        return "OPERATIONAL_EFFICIENCY".equals(objectiveKey)
                || "DISTRIBUTION_ANALYSIS".equals(objectiveKey);
    }

    @Override
    public Map<String, Double> rankingWeightOverrides() {
        return Map.of(
                "anomaly_severity",     0.28,  // outliers = bottlenecks and inefficiencies
                "breadth_of_impact",    0.22,  // systemic inefficiency vs isolated
                "investigation_impact", 0.18,  // root-cause depth on the bottleneck
                "downside_risk",        0.15,  // operational risk if unaddressed
                "confidence",           0.10,
                "volatility",           0.07
        );
    }

    @Override
    public String synthesisSystemExtension() {
        return """
                PLAYBOOK: Operational Efficiency Analysis

                ANALYTICAL LENS:
                - Lead with the most costly bottleneck — quantify the efficiency gap in concrete terms.
                - Distinguish between SYSTEMIC inefficiency (affects most entities) vs ISOLATED (specific segment).
                - Identify HIGH-COST OUTLIERS: entities whose cost or latency is materially above the median.
                - Express inefficiency in UNIT ECONOMICS terms where possible (cost per unit, time per unit).
                - State the OPERATIONAL RISK: what happens to capacity/margin if this persists?
                - Recommend one immediate intervention with the highest efficiency ROI.
                - Flag where idle capacity or underutilisation is masking a latent bottleneck.

                OUTPUT PRIORITIES (in order):
                1. The most material bottleneck with its cost/time impact quantified
                2. Whether the problem is systemic or isolated
                3. Unit economics degradation if detectable
                4. Operational risk trajectory
                5. Highest-ROI optimization action
                """;
    }

    @Override
    public List<String> investigationPriorities() {
        return List.of("bottleneck_severity", "unit_cost_outliers", "utilization_gap",
                       "throughput_variance", "idle_time_concentration");
    }

    @Override
    public List<String> businessContextHints() {
        return List.of(
                "Operational inefficiency compounds — small throughput losses become large margin losses at scale.",
                "Outlier segments running at 2x median cost often reveal process or tooling failures.",
                "Idle capacity > 30% in a cost centre signals structural over-investment.",
                "Unit economics deterioration precedes P&L impact by 1-2 reporting periods."
        );
    }
}
