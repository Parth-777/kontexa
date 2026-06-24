package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent;

/**
 * Canonical analytical intents — dataset-agnostic, schema-driven.
 */
public enum AnalysisIntent {
    RANKING,
    CONTRIBUTION,
    COMPARISON,
    DISTRIBUTION,
    RELATIONSHIP,
    TREND,
    SCALAR;

    public static AnalysisIntent fromInvestigation(AnalyticalInvestigationIntent intent) {
        if (intent == null) return DISTRIBUTION;
        return switch (intent) {
            case RANKING, EFFICIENCY -> RANKING;
            case CONTRIBUTION, SHARE_OF_TOTAL -> CONTRIBUTION;
            case COMPARISON -> COMPARISON;
            case DISTRIBUTION -> DISTRIBUTION;
            case RELATIONSHIP -> RELATIONSHIP;
            case TREND -> TREND;
            case EXACT_LOOKUP -> DISTRIBUTION;
        };
    }

    public static AnalysisIntent fromAnalyticalType(AnalyticalIntentType type) {
        if (type == null) return DISTRIBUTION;
        return switch (type) {
            case RANKING, STRATEGIC_PRIORITIZATION -> RANKING;
            case CONTRIBUTION, COMPOSITION -> CONTRIBUTION;
            case COMPARISON -> COMPARISON;
            case DISTRIBUTION, SEGMENTATION -> DISTRIBUTION;
            case RELATIONSHIP -> RELATIONSHIP;
            case TREND_ANALYSIS, FORECASTING -> TREND;
            default -> DISTRIBUTION;
        };
    }

    public AnalyticalIntentKind sqlKind() {
        return switch (this) {
            case RANKING -> AnalyticalIntentKind.RANKING;
            case CONTRIBUTION -> AnalyticalIntentKind.CONTRIBUTION;
            case COMPARISON -> AnalyticalIntentKind.COMPARISON;
            case DISTRIBUTION -> AnalyticalIntentKind.DISTRIBUTION;
            case RELATIONSHIP -> AnalyticalIntentKind.RELATIONSHIP;
            case TREND -> AnalyticalIntentKind.TREND;
            case SCALAR -> AnalyticalIntentKind.CONTRIBUTION;
        };
    }

    public boolean requiresDimension() {
        return this != RELATIONSHIP;
    }

    public AnalyticalIntentType toAnalyticalIntentType() {
        return switch (this) {
            case RANKING -> AnalyticalIntentType.RANKING;
            case CONTRIBUTION -> AnalyticalIntentType.CONTRIBUTION;
            case COMPARISON -> AnalyticalIntentType.COMPARISON;
            case DISTRIBUTION -> AnalyticalIntentType.DISTRIBUTION;
            case RELATIONSHIP -> AnalyticalIntentType.RELATIONSHIP;
            case TREND -> AnalyticalIntentType.TREND_ANALYSIS;
            case SCALAR -> AnalyticalIntentType.CONTRIBUTION;
        };
    }
}
