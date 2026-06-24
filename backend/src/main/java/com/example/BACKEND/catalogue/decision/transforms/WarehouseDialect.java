package com.example.BACKEND.catalogue.decision.transforms;

/**
 * Warehouse-specific SQL expression generation for temporal derivations.
 */
public interface WarehouseDialect {

    String extractHour(String timestampColumn);

    String extractDayOfWeek(String timestampColumn);

    String extractWeek(String timestampColumn);

    String extractMonth(String timestampColumn);

    String extractQuarter(String timestampColumn);

    String extractYear(String timestampColumn);

    String weekendDayType(String timestampColumn);

    String weekdayName(String timestampColumn);
}
