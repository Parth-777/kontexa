package com.example.BACKEND.catalogue.decision.execution;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;

import java.util.List;
import java.util.Map;

/**
 * Aggregated output of the {@link DynamicAnalyticalExecutionEngine}.
 *
 * Contains fully-computed, entity-level analytical findings derived from
 * raw warehouse rows. These are passed to the synthesis prompt so the LLM
 * reasons from computed discoveries — not from shallow metric summaries.
 *
 * Key distinction from {@link com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult}:
 *   AnalyticalDepthResult   → structural statistics on the raw row set
 *   ExecutionFindings       → entity-level construction, ranking, and specific discoveries
 *
 * The LLM should treat structural findings as pre-computed facts (OBSERVATIONS).
 */
public record ExecutionFindings(
        List<ConstructedEntity>    entities,
        List<RankedEntity>         primaryRanking,
        List<RankedEntity>         efficiencyRanking,
        StatisticalContext         statisticalContext,
        List<StructuralFinding>    findings,
        MaterializedQueryResult    materializedResult   // nullable: grouped aggregation evidence
) {

    // ─── ConstructedEntity ───────────────────────────────────────────────

    /**
     * A dynamically constructed business entity (route, segment, cohort, zone, etc.)
     * with fully aggregated metrics from grouped raw rows.
     */
    public record ConstructedEntity(
            String              entityKey,
            String              entityType,   // ROUTE, ZONE, SEGMENT, COHORT, etc.
            Map<String, Double> metrics,
            long                sampleSize
    ) {}

    // ─── RankedEntity ────────────────────────────────────────────────────

    /**
     * An entity positioned within its peer distribution with rank, percentile, and tier.
     */
    public record RankedEntity(
            int    rank,
            String entityKey,
            String rankingDimension,
            double value,
            double peerAverage,
            double multiplierVsAverage,  // e.g. 2.4x the average
            double percentileRank,
            String tier                  // TOP_DECILE / TOP_QUARTILE / ABOVE_AVERAGE / etc.
    ) {}

    // ─── StatisticalContext ──────────────────────────────────────────────

    /**
     * Statistical context about the entities analyzed — sample sizes,
     * significance, and what was filtered vs. retained.
     */
    public record StatisticalContext(
            int    totalEntitiesConstructed,
            int    entitiesAfterSignificanceFilter,
            int    outliersSuppressed,
            int    minimumSampleUsed,
            double peerAveragePrimaryMetric,
            double topDecileThreshold,
            String coverageNote
    ) {}

    // ─── StructuralFinding ───────────────────────────────────────────────

    /**
     * A specific, quantified analytical discovery derived from computed evidence.
     *
     * Examples of correct findings:
     *   "Airport corridors generate 2.4x higher revenue/minute than the network average."
     *   "Top 3 zones account for 47% of total revenue despite representing 12% of volume."
     *   "Zone 132 efficiency exceeds peer average by 1.8x with 94th percentile revenue/trip."
     *
     * Findings must be specific, quantified, and non-obvious.
     * They must NEVER be vague placeholders or restated averages.
     */
    public record StructuralFinding(
            String  findingText,
            double  magnitude,          // the key number underpinning this finding
            String  evidenceBasis,      // what was computed to derive this
            boolean isNonObvious        // true if this would not emerge from simple aggregation
    ) {}

    // ─── factories ───────────────────────────────────────────────────────

    public static ExecutionFindings empty() {
        return new ExecutionFindings(
                List.of(), List.of(), List.of(),
                new StatisticalContext(0, 0, 0, 0, 0, 0, "Insufficient data for execution."),
                List.of(),
                null
        );
    }

    public boolean hasContent() {
        return !findings.isEmpty() || !primaryRanking.isEmpty() || !entities.isEmpty()
                || (materializedResult != null && materializedResult.hasContent());
    }

    /** Replace grouped evidence with candidate-selection winner. */
    public ExecutionFindings withMaterializedResult(MaterializedQueryResult mat) {
        return new ExecutionFindings(
                entities, primaryRanking, efficiencyRanking, statisticalContext, findings, mat);
    }
}
