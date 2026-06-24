package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;

import java.util.List;
import java.util.UUID;

/**
 * Narrative traceable to finding id, metric, aggregation, and statistical evidence.
 */
public record EvidenceBackedNarrative(
        String              findingId,
        String              metricKey,
        String              metricLabel,
        AggregationType     aggregation,
        DenominatorContext  denominator,
        List<EvidenceClaim> claims,
        double              statisticalStrength
) {
    public static EvidenceBackedNarrative create(
            String findingType,
            String metricKey,
            String metricLabel,
            AggregationType aggregation,
            DenominatorContext denominator,
            String narrative,
            double topValue,
            double shareOrMagnitude,
            double statisticalStrength
    ) {
        String id = findingType + "-" + UUID.randomUUID().toString().substring(0, 8);
        EvidenceClaim claim = new EvidenceClaim(
                narrative,
                id + ".primary",
                metricKey,
                aggregation.name(),
                topValue,
                shareOrMagnitude,
                denominator != null ? denominator.shareOf() : "total"
        );
        return new EvidenceBackedNarrative(
                id, metricKey, metricLabel, aggregation, denominator,
                List.of(claim), statisticalStrength);
    }

    public record EvidenceClaim(
            String sentence,
            String evidenceRef,
            String metric,
            String aggregation,
            double value,
            double magnitude,
            String denominatorDescription
    ) {}
}
