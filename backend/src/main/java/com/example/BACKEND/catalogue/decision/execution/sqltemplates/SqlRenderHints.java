package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;

/**
 * SQL rendering overrides derived from {@link StructuredPlanProjection}.
 */
public record SqlRenderHints(
        String primaryAggregation,
        String orderColumn,
        String orderDirection,
        Integer resultLimit,
        String timeGrain
) {
    public static SqlRenderHints fromAnalysisPlan(AnalysisPlan plan) {
        if (plan == null || plan.structuredProjection() == null) {
            return null;
        }
        StructuredPlanProjection p = plan.structuredProjection();
        if (!p.hasPrimaryAggregation() && !p.hasOrdering() && p.resultLimit() == null && !p.hasTimeGrain()) {
            return null;
        }
        return new SqlRenderHints(
                p.primaryAggregation(),
                p.orderColumn(),
                p.orderDirection(),
                p.resultLimit(),
                p.timeGrain());
    }

    public AggregationSpec resolveAggregation(AnalyticalIntentKind intent, String metricColumn) {
        if (primaryAggregation == null || primaryAggregation.isBlank()) {
            return null;
        }
        String alias = SqlColumnAliases.metricValueAlias(metricColumn);
        return switch (primaryAggregation.toUpperCase()) {
            case "SUM" -> AggregationSpec.sumWithShare(alias);
            case "AVG" -> AggregationSpec.avg(alias);
            case "COUNT" -> AggregationSpec.countDistribution();
            default -> null;
        };
    }
}
