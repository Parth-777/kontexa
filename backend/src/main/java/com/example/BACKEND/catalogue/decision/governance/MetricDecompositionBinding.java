package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;

/**
 * Enforced metric semantics for a decomposition plan.
 */
public record MetricDecompositionBinding(
        String           metricColumn,
        String           metricLabel,
        AggregationType  aggregation,
        String           groupingColumn,
        String           groupingLabel,
        DenominatorContext denominator
) {}
