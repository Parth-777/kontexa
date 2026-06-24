package com.example.BACKEND.catalogue.decision.execution.framework;

import com.example.BACKEND.catalogue.decision.execution.framework.ComputationBlueprint.ComputationStrategy;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

/**
 * Maps each {@link AnalyticalIntentType} to a {@link ComputationBlueprint}.
 *
 * This mapping is the central analytical decision table of the framework.
 * It determines WHAT computational investigation is performed for each
 * question type — entirely independent of the underlying dataset schema.
 *
 * The blueprint is further adapted at runtime by {@link IntentDrivenComputationFramework}
 * based on what the {@link SchemaProfile} reveals is actually available.
 *
 * Design principle: if the data doesn't support a required computation,
 * the framework degrades gracefully rather than fabricating findings.
 */
@Component
public class IntentComputationMap {

    public ComputationBlueprint blueprintFor(AnalyticalIntentType intentType, SchemaProfile profile) {
        return switch (intentType) {

            case CONTRIBUTION -> new ComputationBlueprint(
                    ComputationStrategy.CONTRIBUTION_ANALYSIS,
                    1,          // single-dimension entity
                    false,      // no efficiency ratio needed
                    true,       // concentration: top-N share and Gini
                    false,
                    false,
                    true,       // peer comparison: each entity vs total average
                    false,
                    true,
                    2, 20,
                    "Compute each entity's share of total, concentration index, and above/below-average positioning"
            );

            case RANKING -> new ComputationBlueprint(
                    ComputationStrategy.GENERAL_RANKING,
                    1,
                    profile.hasEfficiencyPair(),  // if we have value+volume, derive efficiency
                    true,
                    false, false,
                    true, false, true,
                    3, 15,
                    "Rank entities by primary metric; compute percentile and peer comparison"
            );

            case STRATEGIC_PRIORITIZATION -> new ComputationBlueprint(
                    ComputationStrategy.EFFICIENCY_RANKING,
                    compositeEntityDepth(profile),  // routes (2) or zones (1) — detected from schema
                    true,
                    true,
                    false,
                    false,
                    true,
                    false,
                    true,
                    5, 12,
                    "Rank entities by composite strategic score: primary value + efficiency + concentration risk"
            );

            case SEGMENTATION -> new ComputationBlueprint(
                    ComputationStrategy.SEGMENTATION_ANALYSIS,
                    1,
                    profile.hasEfficiencyPair(),
                    true,
                    false, false,
                    true, false, true,
                    2, 20,
                    "Group by dimension, compute per-segment aggregates, rank by value and efficiency"
            );

            case COMPARISON -> new ComputationBlueprint(
                    ComputationStrategy.GENERAL_RANKING,
                    1,
                    false,
                    false,
                    profile.hasTime(),  // if time column exists, compute period deltas
                    false,
                    true,
                    false,
                    true,
                    2, 10,
                    "Compare entities or periods; compute absolute and percentage delta"
            );

            case ANOMALY_DETECTION -> new ComputationBlueprint(
                    ComputationStrategy.ANOMALY_DETECTION,
                    1,
                    profile.hasEfficiencyPair(),
                    false,
                    profile.hasTime(),
                    false,
                    true,
                    true,   // z-score outlier detection
                    true,
                    3, 10,
                    "Detect entities with statistically significant deviation from peer norm (z-score ≥ 2σ)"
            );

            case TREND_ANALYSIS -> new ComputationBlueprint(
                    ComputationStrategy.TREND_ANALYSIS,
                    1,
                    false,
                    false,
                    true,   // time-series deltas
                    false,
                    true,
                    false,
                    false,
                    1, 12,
                    "Compute period-over-period deltas, growth rate, and acceleration/deceleration signal"
            );

            case ROOT_CAUSE_INVESTIGATION -> new ComputationBlueprint(
                    ComputationStrategy.ROOT_CAUSE_DECOMPOSITION,
                    1,
                    false,
                    true,   // concentration: which dimension explains most of the change
                    profile.hasTime(),
                    true,   // decompose: attribute change to each dimension
                    true,
                    false,
                    true,
                    2, 15,
                    "Decompose total change by dimensional contribution; identify dominant driver segment"
            );

            case FORECASTING -> new ComputationBlueprint(
                    ComputationStrategy.FORECASTING_ANALYSIS,
                    1,
                    false,
                    false,
                    true,
                    false,
                    true,
                    false,
                    false,
                    3, 8,
                    "Compute trend trajectory from observed periods; project forward with confidence estimate"
            );

            default -> new ComputationBlueprint(
                    ComputationStrategy.GENERAL_RANKING,
                    1,
                    profile.hasEfficiencyPair(),
                    false, false, false,
                    true, false, true,
                    2, 15,
                    "General analytical ranking with peer comparison"
            );
        };
    }

    /**
     * Determines whether to construct composite entities (depth=2) by checking
     * if the schema has at least 2 distinct low-cardinality dimension columns.
     * This detects route-like structures (A→B) without hardcoding anything.
     */
    private int compositeEntityDepth(SchemaProfile profile) {
        return profile.dimensions().size() >= 2 ? 2 : 1;
    }
}
