package com.example.BACKEND.catalogue.decision.ranking;

import java.util.Map;

/**
 * Extension point for scoring strategies.
 *
 * Swap the default {@link DefaultRankingPolicy} for tenant-specific overrides
 * without changing the {@link MaterialityRankingEngine}.
 */
public interface RankingPolicy {

    String policyVersion();

    /**
     * Score a feature vector in [0.0, 1.0].
     *
     * @param features   extracted features from a single evidence object
     * @param tenantId   allows tenant-level weight overrides
     */
    double score(Map<String, Double> features, String tenantId);
}
