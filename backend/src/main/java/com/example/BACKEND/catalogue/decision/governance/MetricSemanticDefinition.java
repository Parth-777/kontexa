package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.*;

import java.util.List;
import java.util.Set;

/**
 * Semantic contract for a governed metric.
 */
public record MetricSemanticDefinition(
        String           metricKey,
        String           displayLabel,
        AggregationType  aggregationType,
        AdditiveScope    additiveScope,
        List<String>     validGroupings,
        BusinessMeaning  businessMeaning,
        MetricUnit       unit,
        String           numeratorMetric,
        String           denominatorMetric,
        boolean          requiresDenominatorForShare
) {
    public boolean allowsGrouping(String dimension) {
        if (dimension == null || validGroupings == null) return false;
        String d = dimension.toLowerCase();
        return validGroupings.stream().anyMatch(g -> d.contains(g.toLowerCase()));
    }

    public boolean isRatioOrRate() {
        return aggregationType == AggregationType.RATIO || aggregationType == AggregationType.RATE;
    }

    public boolean isFullyAdditive() {
        return additiveScope == AdditiveScope.FULLY_ADDITIVE;
    }

    public static final Set<AggregationType> RANKABLE_AGGREGATIONS = Set.of(
            AggregationType.SUM, AggregationType.COUNT, AggregationType.DISTINCT_COUNT, AggregationType.AVG
    );
}
