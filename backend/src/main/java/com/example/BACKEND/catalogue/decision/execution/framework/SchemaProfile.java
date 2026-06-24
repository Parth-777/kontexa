package com.example.BACKEND.catalogue.decision.execution.framework;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The fully classified schema profile of a result set.
 *
 * Produced by {@link SchemaProfiler} from raw row data.
 * Consumed by all framework engines to make computation decisions
 * without any domain-specific column name knowledge.
 */
public record SchemaProfile(
        List<ColumnProfile> columns,
        int                 totalRows
) {

    // ─── convenience accessors ───────────────────────────────────────────

    public List<ColumnProfile> dimensions() {
        return byRole(ColumnRole.DIMENSION);
    }

    public List<ColumnProfile> valueColumns() {
        return byRole(ColumnRole.VALUE);
    }

    public List<ColumnProfile> volumeColumns() {
        return byRole(ColumnRole.VOLUME);
    }

    public List<ColumnProfile> timeColumns() {
        return byRole(ColumnRole.TIME_BUCKET);
    }

    public List<ColumnProfile> rateColumns() {
        return byRole(ColumnRole.RATE);
    }

    /** Primary dimension — the lowest-cardinality grouping column. */
    public ColumnProfile primaryDimension() {
        return dimensions().stream()
                .min(java.util.Comparator.comparingInt(ColumnProfile::cardinality))
                .orElse(null);
    }

    /** Primary value column — the highest-mean numeric metric. */
    public ColumnProfile primaryValue() {
        return valueColumns().stream()
                .max(java.util.Comparator.comparingDouble(ColumnProfile::mean))
                .orElse(null);
    }

    /** Primary volume column — the most count-like column. */
    public ColumnProfile primaryVolume() {
        return volumeColumns().stream()
                .max(java.util.Comparator.comparingInt(ColumnProfile::cardinality))
                .orElse(null);
    }

    public boolean hasDimensions()  { return !dimensions().isEmpty(); }
    public boolean hasValues()      { return !valueColumns().isEmpty(); }
    public boolean hasVolume()      { return !volumeColumns().isEmpty(); }
    public boolean hasTime()        { return !timeColumns().isEmpty(); }

    public boolean hasEfficiencyPair() {
        return hasValues() && (hasVolume() || hasTime());
    }

    /** All numeric columns regardless of role. */
    public List<ColumnProfile> numericColumns() {
        return columns.stream().filter(ColumnProfile::isNumeric).collect(Collectors.toList());
    }

    /** Map from column name to profile for O(1) lookup. */
    public Map<String, ColumnProfile> byName() {
        return columns.stream().collect(Collectors.toMap(ColumnProfile::columnName, c -> c));
    }

    private List<ColumnProfile> byRole(ColumnRole role) {
        return columns.stream()
                .filter(c -> c.role() == role)
                .collect(Collectors.toList());
    }
}
