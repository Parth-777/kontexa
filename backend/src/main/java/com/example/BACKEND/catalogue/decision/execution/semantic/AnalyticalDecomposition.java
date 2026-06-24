package com.example.BACKEND.catalogue.decision.execution.semantic;

import com.example.BACKEND.catalogue.decision.grounding.SemanticFieldKind;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;

import java.util.List;

/**
 * Structured decomposition of a natural-language question into typed analytical targets.
 *
 * Produced by {@link AnalyticalQueryDecomposer} from an {@link
 * com.example.BACKEND.catalogue.decision.planning.InvestigationPlan} + resolved schema.
 *
 * This is the contract that {@link AnalyticalPlanCompiler} consumes to produce SQL.
 * No NLP or raw question text enters after this point.
 *
 * All fields are intentionally generic — no domain names, no column names.
 * Column resolution happens from semantic roles, not hardcoded identifiers.
 */
public record AnalyticalDecomposition(
        AnalyticalIntentType        intentType,
        String                      tableRef,
        List<DimensionTarget>       groupingDimensions,  // what to GROUP BY
        List<MetricTarget>          metrics,             // what to aggregate
        List<DerivedMetricTarget>   derivedMetrics,      // ratio/efficiency columns to compute
        TemporalSpec                temporalSpec,        // time derivation requirements
        RankingSpec                 rankingSpec,         // how to order/limit
        boolean                     requiresConcentration // add share_pct window function
) {

    /** A grouping dimension resolved from the schema. */
    public record DimensionTarget(
            String  columnName,      // physical column name
            String  semanticRole,    // TIME_DIMENSION / ENTITY_DIMENSION / SEGMENT_DIMENSION
            boolean derived,         // true = computed column (e.g. EXTRACT(HOUR FROM col))
            String  derivationExpr,  // e.g. "EXTRACT(HOUR FROM tpep_pickup_datetime)"
            String  displayLabel     // human-readable name for findings
    ) {
        /** Explicit semantic classification — dimensions never produce values. */
        public SemanticFieldKind fieldKind() {
            if ("TIME_DIMENSION".equals(semanticRole)) return SemanticFieldKind.TEMPORAL_DIMENSION;
            if ("ENTITY_DIMENSION".equals(semanticRole) || "SEGMENT_DIMENSION".equals(semanticRole)) {
                return SemanticFieldKind.CATEGORICAL_DIMENSION;
            }
            if (columnName != null && columnName.toLowerCase().contains("id")) {
                return SemanticFieldKind.IDENTIFIER;
            }
            return SemanticFieldKind.DIMENSION;
        }
    }

    /** A metric to aggregate. */
    public record MetricTarget(
            String columnName,
            String aggregation,   // SUM / AVG / COUNT / MAX / MIN
            String alias,         // output column alias
            String semanticRole   // VALUE_METRIC / VOLUME_METRIC
    ) {
        public SemanticFieldKind fieldKind() {
            return SemanticFieldKind.METRIC;
        }
    }

    /** A derived metric column computed from two aggregated metrics. */
    public record DerivedMetricTarget(
            String numeratorAlias,    // alias of the metric to divide
            String denominatorAlias,  // alias of the metric to divide by
            String outputAlias        // e.g. "efficiency_ratio"
    ) {}

    /** Temporal derivation specification. */
    public record TemporalSpec(
            String            sourceColumn,     // raw timestamp column name
            List<Granularity> granularities,    // which time buckets to derive
            boolean           hasTemporalData   // false = no timestamp found
    ) {
        public enum Granularity { HOUR_OF_DAY, WEEKDAY, MONTH, QUARTER, YEAR }
    }

    /** Ranking / output shaping. */
    public record RankingSpec(
            String  orderByAlias,   // alias to ORDER BY
            boolean descending,
            int     limit
    ) {}
}
