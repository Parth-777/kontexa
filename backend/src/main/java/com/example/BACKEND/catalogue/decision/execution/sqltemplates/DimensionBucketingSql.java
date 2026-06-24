package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import java.util.Locale;

/**
 * Fixed CASE-bucket expressions — never invented dynamically by the planner.
 */
public final class DimensionBucketingSql {

    private DimensionBucketingSql() {}

    public static String tripDistanceBucket(String column) {
        String c = sanitize(column);
        return """
                CASE
                  WHEN %s < 1 THEN '0-1'
                  WHEN %s < 3 THEN '1-3'
                  WHEN %s < 5 THEN '3-5'
                  WHEN %s < 10 THEN '5-10'
                  WHEN %s < 20 THEN '10-20'
                  ELSE '20+'
                END""".formatted(c, c, c, c, c);
    }

    public static String currencyAmountBucket(String column) {
        String c = sanitize(column);
        return """
                CASE
                  WHEN %s < 5 THEN '0-5'
                  WHEN %s < 10 THEN '5-10'
                  WHEN %s < 20 THEN '10-20'
                  WHEN %s < 50 THEN '20-50'
                  WHEN %s < 100 THEN '50-100'
                  ELSE '100+'
                END""".formatted(c, c, c, c, c);
    }

    public static String hourOfDay(String datetimeColumn) {
        return "EXTRACT(HOUR FROM " + sanitize(datetimeColumn) + ")";
    }

    public static String weekendFlag(String datetimeColumn) {
        return "CASE WHEN EXTRACT(DAYOFWEEK FROM " + sanitize(datetimeColumn)
                + ") IN (1, 7) THEN 'Weekend' ELSE 'Weekday' END";
    }

    public static String airportFlag(String airportFeeColumn) {
        return "CASE WHEN COALESCE(" + sanitize(airportFeeColumn)
                + ", 0) > 0 THEN 'Airport' ELSE 'Non-airport' END";
    }

    public static String timeGrainExpression(String column, String grain) {
        if (column == null || column.isBlank() || grain == null || grain.isBlank()) {
            return sanitize(column);
        }
        return "DATE_TRUNC(" + sanitize(column) + ", " + grain.toUpperCase(Locale.ROOT) + ")";
    }

    public static String timeGrainAlias(String grain) {
        if (grain == null || grain.isBlank()) {
            return "time_period";
        }
        return grain.toLowerCase(Locale.ROOT) + "_period";
    }

    public static String resolveBucketExpression(String dimensionColumn) {
        if (dimensionColumn == null || dimensionColumn.isBlank()) return sanitize("segment");
        String lower = dimensionColumn.toLowerCase(Locale.ROOT);
        if (lower.contains("airport")) {
            return airportFlag("airport_fee");
        }
        if (lower.contains("trip_distance") || lower.equals("distance")) {
            return tripDistanceBucket(dimensionColumn);
        }
        if (lower.contains("fare_amount")) return currencyAmountBucket(dimensionColumn);
        if (lower.contains("tip_amount")) return currencyAmountBucket(dimensionColumn);
        if (lower.contains("pickup_datetime") || lower.contains("datetime")) {
            return hourOfDay(dimensionColumn);
        }
        if (lower.contains("pulocation") || lower.contains("pickup_zone") || lower.contains("zone")) {
            return sanitize(dimensionColumn);
        }
        if ("weekend_flag".equals(lower) || "weekday".equals(lower)) return sanitize(dimensionColumn);
        if (lower.contains("datetime") && lower.contains("weekend")) return weekendFlag(dimensionColumn);
        return sanitize(dimensionColumn);
    }

    public static String bucketAlias(String dimensionColumn) {
        if (dimensionColumn == null || dimensionColumn.isBlank()) return "segment";
        String lower = dimensionColumn.toLowerCase(Locale.ROOT);
        if (lower.contains("distance")) return "trip_distance_bucket";
        if (lower.contains("datetime") || lower.contains("pickup")) return "hour_of_day";
        if (lower.contains("zone") || lower.contains("location")) return "pickup_zone";
        if ("weekend_flag".equals(lower)) return "weekend_flag";
        if ("weekday".equals(lower)) return "weekday";
        if (lower.contains("datetime") && lower.contains("weekend")) return "day_type";
        return dimensionColumn + "_bucket";
    }

    private static String sanitize(String column) {
        if (column == null || column.isBlank()) return "segment";
        return column.trim();
    }

    /** Delegates to {@link com.example.BACKEND.catalogue.decision.transforms.BucketizationEngine} patterns. */
    public static String tripDistanceBuckets(String column) {
        return new com.example.BACKEND.catalogue.decision.transforms.BucketizationEngine()
                .tripDistanceBuckets(column);
    }
}
