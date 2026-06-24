package com.example.BACKEND.catalogue.decision.execution.framework;

/**
 * The functional role of a column as discovered by {@link SchemaProfiler}.
 *
 * Classification is purely structural — derived from statistical properties
 * and generic naming patterns. No domain-specific logic.
 *
 * ┌──────────────┬──────────────────────────────────────────────────────────┐
 * │ DIMENSION    │ Low-cardinality column used to group/slice entities.      │
 * │              │ Detected by: non-numeric OR cardinality < 15% of rows.   │
 * │              │ Examples across any domain: zone, segment, product,       │
 * │              │ account, region, supplier, campaign, facility             │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ VALUE        │ High-magnitude numeric column representing output/output. │
 * │              │ Detected by: high mean, right-skewed, or semantic signal. │
 * │              │ Generic terms: total, amount, sum, value, cost, price     │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ VOLUME       │ Count-like numeric column. Integer-dominant, bounded.     │
 * │              │ Generic terms: count, qty, num, orders, events, records   │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ TIME_BUCKET  │ Temporal or period column. Used for trend analysis.       │
 * │              │ Generic terms: hour, day, week, month, year, period,      │
 * │              │ quarter, date, timestamp, time                            │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ RATE         │ Bounded [0, 1] numeric. Ratio, percentage, probability.   │
 * │              │ Generic terms: rate, ratio, pct, share, fraction          │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ IDENTIFIER   │ High-cardinality string — primary key, hash, UUID.        │
 * │              │ Too granular to use as grouping dimension directly.       │
 * ├──────────────┼──────────────────────────────────────────────────────────┤
 * │ UNKNOWN      │ Could not classify with sufficient confidence.            │
 * └──────────────┴──────────────────────────────────────────────────────────┘
 */
public enum ColumnRole {
    DIMENSION,
    VALUE,
    VOLUME,
    TIME_BUCKET,
    RATE,
    IDENTIFIER,
    UNKNOWN
}
