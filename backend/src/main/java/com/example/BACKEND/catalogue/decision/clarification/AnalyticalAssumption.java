package com.example.BACKEND.catalogue.decision.clarification;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;

import java.util.List;

/**
 * Explicit assumptions made when resolving an ambiguous analytical question.
 */
public record AnalyticalAssumption(
        String               interpretedQuestion,
        String               primaryMetric,
        String               primaryMetricLabel,
        String               secondaryMetric,
        String               grouping,
        AggregationType      aggregation,
        AnalyticalIntentType intent,
        List<String>         assumptions,
        double               resolutionConfidence,
        boolean              ambiguous,
        String               ambiguityNote
) {}
