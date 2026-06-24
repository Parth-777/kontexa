package com.example.BACKEND.catalogue.decision.ranking;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ranks evidence objects by materiality — a composite score that weighs
 * data breadth, confidence, and strategic importance.
 *
 * The ranking policy is pluggable: inject a different {@link RankingPolicy}
 * bean to change scoring logic without touching this class.
 */
@Service
public class MaterialityRankingEngine {

    private static final Logger log = LoggerFactory.getLogger(MaterialityRankingEngine.class);

    private final FeatureExtractor featureExtractor;
    private final RankingPolicy    rankingPolicy;

    public MaterialityRankingEngine(FeatureExtractor featureExtractor, RankingPolicy rankingPolicy) {
        this.featureExtractor = featureExtractor;
        this.rankingPolicy    = rankingPolicy;
    }

    /**
     * Rank evidence using the default policy (no playbook overrides).
     */
    public List<RankedEvidence> rank(List<EvidenceObject> evidence, String tenantId) {
        return rank(evidence, tenantId, Map.of());
    }

    /**
     * Rank evidence with playbook-specific weight overrides.
     * Override weights replace default policy weights for the named features;
     * all other features retain their default policy weights.
     */
    public List<RankedEvidence> rank(
            List<EvidenceObject> evidence,
            String tenantId,
            Map<String, Double> weightOverrides
    ) {
        if (evidence == null || evidence.isEmpty()) return List.of();

        AtomicInteger rankCounter = new AtomicInteger(1);

        return evidence.stream()
                .map(ev -> {
                    Map<String, Double> features = featureExtractor.extract(ev);
                    double score = weightOverrides.isEmpty()
                            ? rankingPolicy.score(features, tenantId)
                            : scoreWithOverrides(features, tenantId, weightOverrides);
                    return new RankedEvidence(ev.evidenceId(), score, 0, features,
                            rankingPolicy.policyVersion());
                })
                .sorted(Comparator.comparingDouble(RankedEvidence::score).reversed())
                .map(re -> new RankedEvidence(re.evidenceId(), re.score(),
                        rankCounter.getAndIncrement(), re.featureVector(), re.policyVersion()))
                .peek(re -> log.debug("[ranking] rank={} evidenceId={} score={}",
                        re.rank(), re.evidenceId(), re.score()))
                .toList();
    }

    private double scoreWithOverrides(
            Map<String, Double> features,
            String tenantId,
            Map<String, Double> overrides
    ) {
        // Start from base policy score then re-weight overridden features
        Map<String, Double> effectiveWeights = new HashMap<>(Map.of(
                "downside_risk",        0.20, "volatility",           0.18,
                "anomaly_severity",     0.15, "confidence",           0.15,
                "comparative_depth",    0.10, "breadth_of_impact",    0.08,
                "investigation_impact", 0.07, "data_breadth",         0.04,
                "data_volume",          0.02, "freshness",            0.01
        ));
        effectiveWeights.putAll(overrides);

        double totalWeight = effectiveWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        double score = 0;
        for (Map.Entry<String, Double> e : effectiveWeights.entrySet()) {
            double fv = clamp(features.getOrDefault(e.getKey(), 0.0));
            score += fv * (e.getValue() / totalWeight);
        }
        return clamp(score);
    }

    private double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
