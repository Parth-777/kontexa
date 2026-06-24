package com.example.BACKEND.catalogue.decision.playbooks;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Playbook: Anomaly Investigation
 *
 * Purpose: Investigate abnormal changes intelligently — assess deviation severity,
 * identify affected entities, determine persistence, and classify systemic vs
 * localised impact.
 *
 * Output: Probable causes, affected segments, severity assessment,
 * escalation priority, confidence score.
 */
@Component
public class AnomalyInvestigationPlaybook implements Playbook {

    @Override
    public String playbookKey() { return "ANOMALY_INVESTIGATION"; }

    @Override
    public String displayName() { return "Anomaly Investigation"; }

    @Override
    public String analyticalPurpose() {
        return "Investigate abnormal signals — determine severity, root cause, affected segments, "
                + "and whether the deviation is systemic or transient.";
    }

    @Override
    public boolean supports(String objectiveKey) {
        return "ANOMALY_INVESTIGATION".equals(objectiveKey)
                || "ANOMALY_DETECTION".equals(objectiveKey);
    }

    @Override
    public Map<String, Double> rankingWeightOverrides() {
        return Map.of(
                "anomaly_severity",     0.30,  // deviation magnitude is the primary signal
                "downside_risk",        0.22,  // anomalies are usually bad news
                "volatility",           0.18,  // how sudden was the change?
                "investigation_impact", 0.15,  // how deep does the investigation tree go?
                "confidence",           0.10,
                "breadth_of_impact",    0.05   // systemic vs localised
        );
    }

    @Override
    public String synthesisSystemExtension() {
        return """
                PLAYBOOK: Anomaly Investigation

                ANALYTICAL LENS:
                - State the anomaly in precise terms: WHAT changed, by HOW MUCH, WHEN.
                - Classify severity: CRITICAL (>30% deviation), SIGNIFICANT (15-30%), NOTABLE (5-15%).
                - Determine PERSISTENCE: is this a one-period spike or a multi-period trend?
                - Classify as SYSTEMIC (broad-based) or LOCALISED (1-2 entities/segments).
                - State the MOST PROBABLE ROOT CAUSE — data anomaly, operational event, or market shift.
                - Assign ESCALATION PRIORITY: who needs to act and by when?
                - Express confidence in the diagnosis — flag if more data is needed to confirm.

                OUTPUT PRIORITIES (in order):
                1. What anomaly occurred, magnitude, and timing
                2. Severity classification and persistence assessment
                3. Systemic vs localised determination
                4. Most probable root cause
                5. Escalation priority and confidence level
                """;
    }

    @Override
    public List<String> investigationPriorities() {
        return List.of("deviation_magnitude", "affected_segment_count", "persistence_periods",
                       "correlation_dimensions", "baseline_comparison");
    }

    @Override
    public List<String> businessContextHints() {
        return List.of(
                "Anomalies that persist across 3+ periods are structural, not noise.",
                "A >20% deviation in a high-revenue entity requires immediate escalation.",
                "Localised anomalies (1-2 segments) suggest operational failure; systemic suggests market event.",
                "Data anomalies (impossible values) must be flagged separately from business anomalies."
        );
    }
}
