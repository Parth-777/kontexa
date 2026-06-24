package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.AnalyticalIntentPatterns.PatternKind;

/**
 * Benchmark case for semantic parser plan generation.
 */
public record SemanticParserBenchmarkCase(
        String               id,
        String               question,
        PatternKind          expectedPattern,
        AnalyticalIntentType expectedIntent,
        String               expectedPrimaryMetric,
        String               expectedDenominatorMetric,
        String               expectedGrouping,
        boolean              expectCompositionRatio,
        String               description
) {}
