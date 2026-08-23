package com.example.BACKEND.catalogue.decision.transforms;

import org.springframework.stereotype.Component;

/**
 * Snowflake SQL fragments for the shared WarehouseDialect port.
 */
@Component
public class SnowflakeDialect implements WarehouseDialect {

    @Override
    public String extractHour(String timestampColumn) {
        return "EXTRACT(HOUR FROM " + col(timestampColumn) + ")";
    }

    @Override
    public String extractDayOfWeek(String timestampColumn) {
        return "DAYOFWEEK(" + col(timestampColumn) + ")";
    }

    @Override
    public String extractWeek(String timestampColumn) {
        return "WEEK(" + col(timestampColumn) + ")";
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
        return "CASE WHEN DAYNAME(" + col(timestampColumn)
                + ") IN ('Sat', 'Sun') THEN 'Weekend' ELSE 'Weekday' END";
    }

    @Override
    public String weekdayName(String timestampColumn) {
        return "DAYNAME(" + col(timestampColumn) + ")";
    }

    @Override
    public String quoteIdent(String ident) {
        String raw = ident == null ? "" : ident.trim();
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String timestampTrunc(String timestampColumn, String grain) {
        return "DATE_TRUNC('" + grain.trim().toUpperCase() + "', " + col(timestampColumn) + ")";
    }

    @Override
    public String safeDivide(String numeratorSql, String denominatorSql) {
        return "DIV0NULL(" + numeratorSql + ", " + denominatorSql + ")";
    }

    @Override
    public String countIf(String conditionSql) {
        return "COUNT_IF(" + conditionSql + ")";
    }

    private String col(String c) {
        return c != null && !c.isBlank() ? c.trim() : "CURRENT_TIMESTAMP";
    }
}
