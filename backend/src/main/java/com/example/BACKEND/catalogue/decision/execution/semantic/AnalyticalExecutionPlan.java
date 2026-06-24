package com.example.BACKEND.catalogue.decision.execution.semantic;

import java.util.List;

/**
 * A fully compiled, SQL-ready analytical execution plan.
 *
 * Produced by {@link AnalyticalPlanCompiler} from an {@link AnalyticalDecomposition}.
 * Consumed exclusively by {@link AnalyticalSQLGenerator} — no other component touches SQL.
 *
 * Each {@link ComputationStep} represents one GROUP BY query to execute against the
 * warehouse.  Multiple steps are generated to maximise the pre-computed evidence
 * available to synthesis (e.g. one per temporal granularity + one entity-level).
 */
public record AnalyticalExecutionPlan(
        String                planId,
        String                tableRef,
        List<ComputationStep> steps
) {

    /**
     * One executable GROUP BY computation.
     *
     * groupByExpressions — list of column names or derived expressions (EXTRACT, DATE_TRUNC)
     * selectExpressions  — full SELECT clause items (aggregations, window functions)
     * orderByClause      — ORDER BY clause (may be null if no ranking needed)
     * limitClause        — LIMIT n (0 = no limit)
     * stepDescription    — what this query computes (for logging and evidence labelling)
     */
    public record ComputationStep(
            String       stepKey,
            StepType     stepType,
            String       tableRef,
            List<String> groupByExpressions,
            List<String> selectExpressions,
            String       orderByClause,       // nullable
            int          limitClause,
            String       stepDescription
    ) {}

    public enum StepType {
        TEMPORAL_GROUPED_RANKING,   // GROUP BY time bucket, ranked by value
        ENTITY_GROUPED_RANKING,     // GROUP BY categorical entity dimension
        COMPOSITE_ENTITY_RANKING,   // GROUP BY two dimensions combined
        EFFICIENCY_RANKING,         // GROUP BY entity, rank by value/volume ratio
        CONTRIBUTION_ANALYSIS,      // GROUP BY entity + window share_pct
        TREND_TIMESERIES,           // GROUP BY time period, ordered chronologically
        GENERAL_SUMMARY             // Fallback aggregate (SUM/AVG/MAX/COUNT)
    }
}
