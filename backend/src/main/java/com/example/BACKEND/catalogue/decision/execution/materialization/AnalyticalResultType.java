package com.example.BACKEND.catalogue.decision.execution.materialization;

/**
 * Shape of a warehouse analytical result after materialization.
 */
public enum AnalyticalResultType {
    /** Ranked or segmented rows (GROUP BY or pre-aggregated entity + metric). */
    GROUPED_RESULT,
    /** Single-row CORR / correlation aggregate (coefficient + sample size). */
    CORRELATION_RESULT,
    /** Single-row scalar aggregate (total, average, count of one metric). */
    SCALAR_RESULT
}
