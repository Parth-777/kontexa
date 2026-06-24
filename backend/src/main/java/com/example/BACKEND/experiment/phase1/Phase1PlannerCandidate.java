package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;

import java.util.List;

/**
 * One factual query interpretation from GPT.
 */
public record Phase1PlannerCandidate(
        AnalysisIntent intent,
        String metric,
        String aggregation,
        List<String> dimensions,
        List<Phase1FilterSpec> filters,
        Phase1OrderingSpec ordering,
        Integer limit,
        double confidence,
        String reasoning
) {}
