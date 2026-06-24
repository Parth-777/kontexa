package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;

/**
 * A ranked analytical interpretation hypothesis for exploratory execution.
 */
public record InterpretationCandidatePlan(
        String               label,
        String               description,
        String               primaryMetric,
        String               primaryMetricLabel,
        String               secondaryMetric,
        String               grouping,
        AggregationType      aggregation,
        AnalyticalIntentType intent,
        double               confidence,
        String               source
) {}
