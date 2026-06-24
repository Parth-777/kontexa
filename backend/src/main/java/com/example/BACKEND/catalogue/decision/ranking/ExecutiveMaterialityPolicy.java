package com.example.BACKEND.catalogue.decision.ranking;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Materiality Engine V2 — executive-grade ranking policy.
 *
 * Scoring dimensions (weights sum to 1.0):
 *
 *   downside_risk        0.20  — executives prioritise bad news that needs action
 *   volatility           0.18  — magnitude of movement (material change)
 *   anomaly_severity     0.15  — how far from expected (z-score proxy)
 *   confidence           0.15  — data trustworthiness
 *   comparative_depth    0.10  — how richly was the signal contextualised?
 *   breadth_of_impact    0.08  — systemic vs concentrated
 *   investigation_impact 0.07  — severity of deepest drilldown finding
 *   data_breadth         0.04  — number of distinct signals
 *   data_volume          0.02  — backing data points
 *   freshness            0.01  — query recency proxy
 *
 * Weights are adjustable per tenant via requestMeta overrides (Phase 2).
 * The objective-specific multiplier boosts the most relevant feature.
 */
@Component
public class ExecutiveMaterialityPolicy implements RankingPolicy {

    private static final String VERSION = "executive-materiality-v2";

    private static final Map<String, Double> DEFAULT_WEIGHTS = Map.of(
            "downside_risk",        0.20,
            "volatility",           0.18,
            "anomaly_severity",     0.15,
            "confidence",           0.15,
            "comparative_depth",    0.10,
            "breadth_of_impact",    0.08,
            "investigation_impact", 0.07,
            "data_breadth",         0.04,
            "data_volume",          0.02,
            "freshness",            0.01
    );

    @Override
    public String policyVersion() { return VERSION; }

    @Override
    public double score(Map<String, Double> features, String tenantId) {
        double score = 0.0;
        for (Map.Entry<String, Double> weight : DEFAULT_WEIGHTS.entrySet()) {
            double featureVal = features.getOrDefault(weight.getKey(), 0.0);
            score += clamp(featureVal) * weight.getValue();
        }
        return clamp(score);
    }

    private double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
