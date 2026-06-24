package com.example.BACKEND.catalogue.decision.transforms;

/**
 * Warehouse-agnostic business concepts that require derivation from raw columns.
 */
public enum SemanticConcept {
    WEEKEND_DAY,
    WEEKDAY,
    HOUR_OF_DAY,
    DAY_OF_WEEK,
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
    TRIP_DISTANCE_BUCKET,
    AIRPORT_RIDE,
    FARE_BUCKET,
    TIP_BUCKET,
    TOP_N_SEGMENT,
    IDENTITY
}
