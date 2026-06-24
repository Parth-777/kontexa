package com.example.BACKEND.catalogue.decision.transforms;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates executable CASE expressions for numeric bucketization.
 */
@Component
public class BucketizationEngine {

    public enum BucketStrategy {
        TRIP_DISTANCE_HEURISTIC,
        FARE_HEURISTIC,
        TIP_HEURISTIC,
        GENERIC_QUANTILE_PLACEHOLDER,
        IDENTITY
    }

    public DerivedDimensionSpec bucketize(
            SemanticConcept concept, String numericColumn, String logicalKey
    ) {
        BucketStrategy strategy = strategyFor(concept, numericColumn);
        List<TransformationStep> steps = new ArrayList<>();
        steps.add(TransformationStep.derived(
                "resolve_numeric", "Resolving numeric column for bucketization",
                numericColumn, null, "Source: " + numericColumn));

        String expression = switch (strategy) {
            case TRIP_DISTANCE_HEURISTIC -> tripDistanceBuckets(numericColumn);
            case FARE_HEURISTIC -> fareBuckets(numericColumn);
            case TIP_HEURISTIC -> tipBuckets(numericColumn);
            case IDENTITY -> numericColumn;
            case GENERIC_QUANTILE_PLACEHOLDER -> genericBuckets(numericColumn);
        };

        String alias = aliasFor(concept, numericColumn);
        steps.add(TransformationStep.derived(
                "apply_bucketization", "Applying " + strategy.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                numericColumn, alias, expression));

        return new DerivedDimensionSpec(
                concept, logicalKey, numericColumn, expression, alias, true, steps);
    }

    public BucketStrategy strategyFor(SemanticConcept concept, String column) {
        if (concept == SemanticConcept.TRIP_DISTANCE_BUCKET) return BucketStrategy.TRIP_DISTANCE_HEURISTIC;
        if (concept == SemanticConcept.FARE_BUCKET) return BucketStrategy.FARE_HEURISTIC;
        if (concept == SemanticConcept.TIP_BUCKET) return BucketStrategy.TIP_HEURISTIC;
        if (column != null) {
            String lower = column.toLowerCase(Locale.ROOT);
            if (lower.contains("distance") || lower.contains("mile")) return BucketStrategy.TRIP_DISTANCE_HEURISTIC;
            if (lower.contains("fare")) return BucketStrategy.FARE_HEURISTIC;
            if (lower.contains("tip")) return BucketStrategy.TIP_HEURISTIC;
        }
        return BucketStrategy.GENERIC_QUANTILE_PLACEHOLDER;
    }

    public String tripDistanceBuckets(String column) {
        String c = sanitize(column);
        return """
                CASE
                  WHEN %s < 1 THEN '0-1'
                  WHEN %s < 3 THEN '1-3'
                  WHEN %s < 5 THEN '3-5'
                  WHEN %s < 10 THEN '5-10'
                  WHEN %s < 20 THEN '10-20'
                  ELSE '20+'
                END""".formatted(c, c, c, c, c);
    }

    public String fareBuckets(String column) {
        String c = sanitize(column);
        return """
                CASE
                  WHEN %s < 10 THEN '0-10'
                  WHEN %s < 25 THEN '10-25'
                  WHEN %s < 50 THEN '25-50'
                  WHEN %s < 100 THEN '50-100'
                  ELSE '100+'
                END""".formatted(c, c, c, c);
    }

    public String tipBuckets(String column) {
        return fareBuckets(column);
    }

    public String genericBuckets(String column) {
        String c = sanitize(column);
        return """
                CASE
                  WHEN %s IS NULL THEN 'unknown'
                  WHEN %s < 0 THEN 'negative'
                  ELSE CAST(ROUND(%s, 0) AS STRING)
                END""".formatted(c, c, c);
    }

    private String aliasFor(SemanticConcept concept, String column) {
        return switch (concept) {
            case TRIP_DISTANCE_BUCKET -> "trip_distance_bucket";
            case FARE_BUCKET -> "fare_amount_bucket";
            case TIP_BUCKET -> "tip_amount_bucket";
            default -> column != null ? column + "_bucket" : "segment_bucket";
        };
    }

    private String sanitize(String column) {
        return column != null && !column.isBlank() ? column.trim() : "value";
    }
}
