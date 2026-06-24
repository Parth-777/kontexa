package com.example.BACKEND.catalogue.decision.ranking;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Default ranking policy — delegates to {@link ExecutiveMaterialityPolicy} (V2).
 *
 * Marked {@code @Primary} so Spring resolves it when multiple {@link RankingPolicy}
 * beans exist.  Swap this class's delegate to change the active scoring strategy.
 */
@Primary
@Component
public class DefaultRankingPolicy implements RankingPolicy {

    private final ExecutiveMaterialityPolicy v2;

    public DefaultRankingPolicy(ExecutiveMaterialityPolicy v2) {
        this.v2 = v2;
    }

    @Override
    public String policyVersion() { return v2.policyVersion(); }

    @Override
    public double score(Map<String, Double> features, String tenantId) {
        return v2.score(features, tenantId);
    }
}
