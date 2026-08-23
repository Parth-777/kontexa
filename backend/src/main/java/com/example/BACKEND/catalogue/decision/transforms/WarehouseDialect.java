package com.example.BACKEND.catalogue.decision.transforms;

/**
 * Warehouse-specific SQL fragments. Semantic intent stays in the common compiler;
 * this port only renders provider SQL.
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

    /** Quote a single identifier segment (table or column name). */
    String quoteIdent(String ident);

    /** TIMESTAMP/DATE truncation for a time grain such as MONTH. */
    String timestampTrunc(String timestampColumn, String grain);

    /** Null-safe division (BigQuery SAFE_DIVIDE). */
    String safeDivide(String numeratorSql, String denominatorSql);

    /** Conditional count (BigQuery COUNTIF). */
    String countIf(String conditionSql);
}
