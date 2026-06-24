package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;

/**
 * An alternative valid interpretation when a question is ambiguous.
 */
public record ClarificationOption(
        String            label,
        String            primaryMetric,
        String            metricLabel,
        String            grouping,
        AggregationType   aggregation,
        AnalyticalIntentType intent,
        String            description
) {}
