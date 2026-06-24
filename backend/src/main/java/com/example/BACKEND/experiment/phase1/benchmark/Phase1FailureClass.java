package com.example.BACKEND.experiment.phase1.benchmark;

/**
 * Failure taxonomy for detailed Phase-1 benchmark reports.
 */
public enum Phase1FailureClass {
    WRONG_METRIC,
    WRONG_DIMENSION,
    WRONG_FILTER,
    WRONG_AGGREGATION,
    WRONG_RANKING_DIRECTION,
    WRONG_INTERPRETATION,
    EXECUTION_FAILED,
    NONE
}
