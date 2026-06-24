package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;

import java.util.List;

/**
 * Ground-truth labels for factual Phase-1 benchmark questions.
 */
public record Phase1FactualCase(
        String datasetId,
        String question,
        String expectedMetric,
        String expectedDimension,
        List<Phase1FilterSpec> expectedFilters,
        String expectedAggregation,
        String expectedOrderDirection,
        Integer expectedLimit,
        AnalysisIntent expectedIntent,
        boolean expectSql
) {
    public Phase1FactualCase(
            String datasetId, String question, String expectedMetric, String expectedDimension,
            List<Phase1FilterSpec> expectedFilters, boolean expectSql
    ) {
        this(datasetId, question, expectedMetric, expectedDimension, expectedFilters,
                "SUM", null, null, AnalysisIntent.CONTRIBUTION, expectSql);
    }
}
