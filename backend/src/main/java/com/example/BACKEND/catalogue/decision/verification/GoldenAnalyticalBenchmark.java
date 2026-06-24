package com.example.BACKEND.catalogue.decision.verification;

import java.util.List;

/**
 * Expected analytical properties for a mandatory benchmark query.
 */
public record GoldenAnalyticalBenchmark(
        String               id,
        String               question,
        String               expectedMetric,
        List<String>         expectedOrdering,
        ValueRange           expectedLeaderSharePct,
        ValueRange           expectedLeaderToTailMultiple,
        ConcentrationPattern concentrationPattern,
        int                  minGroupCount
) {
    public enum ConcentrationPattern {
        SHORT_DISTANCE_SKEW,
        PEAK_HOUR,
        TIP_COMPOSITION,
        WEEKDAY_SKEW,
        ZONE_RANKING,
        FARE_DISTANCE_GRADIENT,
        GENERAL
    }

    public record ValueRange(double min, double max) {
        public boolean contains(double v) {
            return v >= min && v <= max;
        }
    }
}
