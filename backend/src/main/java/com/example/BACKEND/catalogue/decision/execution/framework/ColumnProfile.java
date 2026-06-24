package com.example.BACKEND.catalogue.decision.execution.framework;

/**
 * Statistical and structural profile of a single column.
 *
 * Produced by {@link SchemaProfiler} from raw row data.
 * Used by downstream engines to decide how to use each column
 * without any domain-specific assumptions.
 */
public record ColumnProfile(
        String     columnName,
        ColumnRole role,
        double     mean,
        double     max,
        double     min,
        double     stdDev,
        int        cardinality,    // distinct value count
        int        rowCount,
        boolean    isNumeric,
        boolean    isIntegerDominant,  // >80% of values are whole numbers
        double     cardinalityRatio    // cardinality / rowCount
) {
    /** True if this column should be used as a grouping dimension for entity construction. */
    public boolean isGroupingDimension() {
        return role == ColumnRole.DIMENSION;
    }

    /** True if this column carries a meaningful magnitude for aggregation. */
    public boolean isAggregatable() {
        return role == ColumnRole.VALUE || role == ColumnRole.VOLUME;
    }

    /** True if this column can serve as the numerator in an efficiency ratio. */
    public boolean isEfficiencyNumerator() {
        return role == ColumnRole.VALUE;
    }

    /** True if this column can serve as the denominator in an efficiency ratio. */
    public boolean isEfficiencyDenominator() {
        return role == ColumnRole.VOLUME || role == ColumnRole.TIME_BUCKET;
    }
}
