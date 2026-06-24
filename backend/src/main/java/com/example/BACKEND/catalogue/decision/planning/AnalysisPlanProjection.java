package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;

/**
 * Projects analytical truth fields from {@link AnalysisPlan} without inference or domain defaults.
 */
public final class AnalysisPlanProjection {

    private AnalysisPlanProjection() {}

    public static String groupingColumn(AnalysisPlan contract) {
        if (contract == null) return null;
        if (contract.groupingAlias() != null && !contract.groupingAlias().isBlank()) {
            return contract.groupingAlias();
        }
        return contract.dimension();
    }

    public static String groupingLabel(AnalysisPlan contract) {
        if (contract == null) return "";
        if (contract.dimensionLabel() != null && !contract.dimensionLabel().isBlank()) {
            return contract.dimensionLabel();
        }
        String col = groupingColumn(contract);
        return col != null ? col.replace('_', ' ') : "";
    }

    public static AggregationType aggregation(AnalysisIntent intent) {
        if (intent == null) return AggregationType.SUM;
        return switch (intent.sqlKind()) {
            case EFFICIENCY, RELATIONSHIP -> AggregationType.AVG;
            case DISTRIBUTION -> AggregationType.COUNT;
            case CONTRIBUTION, COMPARISON, RANKING, TREND -> AggregationType.SUM;
        };
    }
}
