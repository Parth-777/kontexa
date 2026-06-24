package com.example.BACKEND.catalogue.decision.semantic;

/**
 * Dimension impact plan: bucket dimension, aggregate metric, compute dominant share.
 */
public record DimensionImpactPlan(
        String dimensionColumn,
        String dimensionLabel,
        String metricColumn,
        String metricLabel,
        String bucketStrategy,
        boolean aggregateByBucket,
        boolean computeDominantShare
) {
    public static DimensionImpactPlan of(
            String dimension, String dimensionLabel,
            String metric, String metricLabel
    ) {
        String bucket = dimension.endsWith("_bucket") ? dimension : dimension + "_bucket";
        return new DimensionImpactPlan(
                dimension, dimensionLabel,
                metric, metricLabel,
                bucket, true, true
        );
    }
}
