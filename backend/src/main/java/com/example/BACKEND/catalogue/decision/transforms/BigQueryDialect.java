package com.example.BACKEND.catalogue.decision.transforms;

import org.springframework.stereotype.Component;

/**
 * BigQuery-native temporal derivation expressions.
 */
@Component
public class BigQueryDialect implements WarehouseDialect {

    @Override
    public String extractHour(String timestampColumn) {
        return "EXTRACT(HOUR FROM " + col(timestampColumn) + ")";
    }

    @Override
    public String extractDayOfWeek(String timestampColumn) {
        return "EXTRACT(DAYOFWEEK FROM " + col(timestampColumn) + ")";
    }

    @Override
    public String extractWeek(String timestampColumn) {
        return "EXTRACT(WEEK FROM " + col(timestampColumn) + ")";
    }

    @Override
    public String extractMonth(String timestampColumn) {
        return "EXTRACT(MONTH FROM " + col(timestampColumn) + ")";
    }

    @Override
    public String extractQuarter(String timestampColumn) {
        return "EXTRACT(QUARTER FROM " + col(timestampColumn) + ")";
    }

    @Override
    public String extractYear(String timestampColumn) {
        return "EXTRACT(YEAR FROM " + col(timestampColumn) + ")";
    }

    @Override
    public String weekendDayType(String timestampColumn) {
        return "CASE WHEN EXTRACT(DAYOFWEEK FROM " + col(timestampColumn)
                + ") IN (1, 7) THEN 'Weekend' ELSE 'Weekday' END";
    }

    @Override
    public String weekdayName(String timestampColumn) {
        return "FORMAT_DATE('%A', DATE(" + col(timestampColumn) + "))";
    }

    private String col(String c) {
        return c != null && !c.isBlank() ? c.trim() : "pickup_datetime";
    }
}
