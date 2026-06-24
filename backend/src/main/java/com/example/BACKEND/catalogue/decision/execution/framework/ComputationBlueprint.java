package com.example.BACKEND.catalogue.decision.execution.framework;

import java.util.List;

/**
 * A generic analytical computation blueprint for a given intent type.
 *
 * The blueprint specifies WHAT to compute and HOW to structure the analysis,
 * without referencing any domain-specific columns or business concepts.
 * Column resolution happens at runtime against the actual {@link SchemaProfile}.
 *
 * Fields:
 *   computationStrategy  — the type of analytical investigation
 *   entityGroupingDepth  — how many dimension columns to use for entity construction
 *                          (1 = single entity, 2 = composite entity like A→B route)
 *   requiresEfficiency   — must compute value/volume derived ratios
 *   requiresConcentration — must compute top-N share and Gini index
 *   requiresTimeSeries   — must compute deltas across time buckets
 *   requiresDecomposition — must split change by dimension contributions
 *   requiresPeerComparison — must compare each entity against peer average
 *   requiresOutlierDetection — must flag statistical anomalies (z-score)
 *   requiresRanking      — must produce ordered entity list
 *   minimumSamplePerEntity — entities below this are filtered before ranking
 *   maxEntitiesInOutput  — cap on ranked output for synthesis token budget
 *   analysisDescription  — human-readable description of what is being computed
 */
public record ComputationBlueprint(
        ComputationStrategy computationStrategy,
        int                 entityGroupingDepth,
        boolean             requiresEfficiency,
        boolean             requiresConcentration,
        boolean             requiresTimeSeries,
        boolean             requiresDecomposition,
        boolean             requiresPeerComparison,
        boolean             requiresOutlierDetection,
        boolean             requiresRanking,
        int                 minimumSamplePerEntity,
        int                 maxEntitiesInOutput,
        String              analysisDescription
) {

    public enum ComputationStrategy {
        /** Rank entities by primary value metric and efficiency ratio. */
        EFFICIENCY_RANKING,

        /** Compute entity contributions as share of total; concentration index. */
        CONTRIBUTION_ANALYSIS,

        /** Compute period-over-period deltas and growth rates. */
        TREND_ANALYSIS,

        /** Decompose a total change by dimensional contribution. */
        ROOT_CAUSE_DECOMPOSITION,

        /** Cluster and compare entity groups by variance. */
        SEGMENTATION_ANALYSIS,

        /** Flag entities with z-score deviation from peer norm. */
        ANOMALY_DETECTION,

        /** General ranking without specific efficiency focus. */
        GENERAL_RANKING,

        /** Forecast from observed trend with confidence interval. */
        FORECASTING_ANALYSIS
    }
}
