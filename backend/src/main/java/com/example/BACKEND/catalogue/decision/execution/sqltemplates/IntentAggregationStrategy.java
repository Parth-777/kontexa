package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent;
import com.example.BACKEND.catalogue.decision.semantics.AnalyticalRelationship;
import org.springframework.stereotype.Component;

/**
 * Maps analytical intent to warehouse aggregation — never inferred from dimension.
 */
@Component
public class IntentAggregationStrategy {

    public AggregationSpec forSqlIntent(AnalyticalIntentKind intent) {
        return forSqlIntent(intent, null);
    }

    public AggregationSpec forSqlIntent(AnalyticalIntentKind intent, String metricColumn) {
        String alias = SqlColumnAliases.metricValueAlias(metricColumn);
        return switch (intent) {
            case CONTRIBUTION, COMPARISON, RANKING, TREND -> AggregationSpec.sumWithShare(alias);
            case DISTRIBUTION -> AggregationSpec.countDistribution();
            case EFFICIENCY -> AggregationSpec.avg(alias);
            case RELATIONSHIP -> AggregationSpec.avg("correlation_coefficient");
        };
    }

    public AggregationSpec forInvestigationIntent(AnalyticalInvestigationIntent intent) {
        return forInvestigationIntent(intent, null);
    }

    public AggregationSpec forInvestigationIntent(
            AnalyticalInvestigationIntent intent, String metricColumn
    ) {
        String alias = SqlColumnAliases.metricValueAlias(metricColumn);
        return switch (intent) {
            case CONTRIBUTION, COMPARISON, RANKING, TREND -> AggregationSpec.sumWithShare(alias);
            case SHARE_OF_TOTAL -> AggregationSpec.sum(alias);
            case DISTRIBUTION -> AggregationSpec.countDistribution();
            case EFFICIENCY -> AggregationSpec.avg(alias);
            case EXACT_LOOKUP -> AggregationSpec.sum(alias);
            case RELATIONSHIP -> AggregationSpec.avg("correlation_coefficient");
        };
    }

    public AggregationSpec forRelationship(AnalyticalRelationship relationship) {
        return forRelationship(relationship, null);
    }

    public AggregationSpec forRelationship(AnalyticalRelationship relationship, String metricColumn) {
        String alias = SqlColumnAliases.metricValueAlias(metricColumn);
        return switch (relationship) {
            case SHARE_OF_TOTAL -> AggregationSpec.sum(alias);
            case DIMENSION_BREAKDOWN, COMPARISON, RANKING, TREND_OVER_TIME ->
                    AggregationSpec.sumWithShare(alias);
            case DISTRIBUTION -> AggregationSpec.countDistribution();
            case EFFICIENCY -> AggregationSpec.avg(alias);
            case EXACT_LOOKUP -> AggregationSpec.sum(alias);
            case METRIC_RELATIONSHIP -> AggregationSpec.avg("correlation_coefficient");
        };
    }

    /**
     * Resolves SQL template intent from business relationship — contribution questions
     * must not route to DISTRIBUTION (AVG/COUNT-only) templates.
     */
    public AnalyticalIntentKind sqlIntentForRelationship(AnalyticalRelationship relationship) {
        return switch (relationship) {
            case SHARE_OF_TOTAL -> AnalyticalIntentKind.CONTRIBUTION;
            case DIMENSION_BREAKDOWN, COMPARISON -> AnalyticalIntentKind.CONTRIBUTION;
            case RANKING -> AnalyticalIntentKind.RANKING;
            case TREND_OVER_TIME -> AnalyticalIntentKind.TREND;
            case DISTRIBUTION -> AnalyticalIntentKind.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalIntentKind.EFFICIENCY;
            case EXACT_LOOKUP -> AnalyticalIntentKind.CONTRIBUTION;
            case METRIC_RELATIONSHIP -> AnalyticalIntentKind.RELATIONSHIP;
        };
    }

    public AnalyticalIntentKind sqlIntentForInvestigation(AnalyticalInvestigationIntent intent) {
        return switch (intent) {
            case CONTRIBUTION -> AnalyticalIntentKind.CONTRIBUTION;
            case COMPARISON -> AnalyticalIntentKind.COMPARISON;
            case RANKING -> AnalyticalIntentKind.RANKING;
            case TREND -> AnalyticalIntentKind.TREND;
            case DISTRIBUTION -> AnalyticalIntentKind.DISTRIBUTION;
            case EFFICIENCY -> AnalyticalIntentKind.EFFICIENCY;
            case SHARE_OF_TOTAL, EXACT_LOOKUP -> AnalyticalIntentKind.CONTRIBUTION;
            case RELATIONSHIP -> AnalyticalIntentKind.RELATIONSHIP;
        };
    }

    public boolean allowsAvgFallback(AnalyticalIntentKind intent) {
        return intent == AnalyticalIntentKind.EFFICIENCY || intent == AnalyticalIntentKind.DISTRIBUTION;
    }
}
