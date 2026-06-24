package com.example.BACKEND.catalogue.decision.planning;

/**
 * Controls how many investigation steps are generated and how deeply
 * the system reasons before synthesis.
 *
 *   MINIMAL  — 1-2 steps, direct computation, no comparative enrichment.
 *              Used by CONTRIBUTION and simple COMPARISON intents.
 *
 *   STANDARD — 3-4 steps, comparative framing, segment analysis.
 *              Used by RANKING, SEGMENTATION, TREND_ANALYSIS.
 *
 *   DEEP     — 5+ steps, weighted scoring, anomaly detection,
 *              concentration analysis, multi-dimensional comparison.
 *              Used by STRATEGIC_PRIORITIZATION, ROOT_CAUSE_INVESTIGATION,
 *              ANOMALY_DETECTION.
 */
public enum PlanDepth {
    MINIMAL,
    STANDARD,
    DEEP
}
