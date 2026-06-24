package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricMatchCandidate;

import java.util.List;

/**
 * Registry-validated metric and dimension binding with confidence scoring.
 */
public record MetricResolution(
        String  primaryMetric,
        String  primaryMetricLabel,
        String  targetMetric,
        String  targetMetricLabel,
        String  relationshipVariable,
        String  relationshipVariableLabel,
        String  dimension,
        String  dimensionLabel,
        String  grouping,
        double  confidence,
        boolean rejected,
        String  rejectionReason,
        List<MetricMatchCandidate> candidates,
        MetricResolutionDebug debug
) {
    public MetricResolution(
            String primaryMetric, String primaryMetricLabel,
            String targetMetric, String targetMetricLabel,
            String dimension, String dimensionLabel,
            String grouping, double confidence,
            boolean rejected, String rejectionReason
    ) {
        this(primaryMetric, primaryMetricLabel, targetMetric, targetMetricLabel,
                null, null, dimension, dimensionLabel, grouping, confidence, rejected, rejectionReason,
                List.of(), null);
    }

    public static MetricResolution rejected(String reason) {
        return new MetricResolution(null, null, null, null, null, null, null, null, null, 0, true, reason,
                List.of(), MetricResolutionDebug.empty(reason));
    }

    public boolean isUsable() {
        return !rejected && primaryMetric != null && !primaryMetric.isBlank() && confidence >= 0.45;
    }

    public boolean isRelationshipAnalysis() {
        return relationshipVariable != null && !relationshipVariable.isBlank()
                && primaryMetric != null && !primaryMetric.isBlank();
    }
}
