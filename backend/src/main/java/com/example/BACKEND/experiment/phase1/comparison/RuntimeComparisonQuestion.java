package com.example.BACKEND.experiment.phase1.comparison;

/**
 * A human-written business question for runtime planner comparison.
 * No ground-truth labels — qualitative side-by-side evaluation only.
 */
public record RuntimeComparisonQuestion(String datasetId, String question) {}
