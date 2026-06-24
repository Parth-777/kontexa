package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import java.util.Locale;

/**
 * Applies ordering and limit hints from structured plans onto generated SQL.
 */
public final class SqlRenderPostProcessor {

    private SqlRenderPostProcessor() {}

    public static String apply(String sql, SqlRenderHints hints) {
        if (sql == null || sql.isBlank() || hints == null) {
            return sql;
        }
        String result = sql;
        if (hints.orderColumn() != null && !hints.orderColumn().isBlank()) {
            result = replaceOrderBy(result, hints.orderColumn(), hints.orderDirection());
        }
        if (hints.resultLimit() != null && hints.resultLimit() > 0) {
            result = replaceOrAppendLimit(result, hints.resultLimit());
        }
        return result;
    }

    private static String replaceOrderBy(String sql, String column, String direction) {
        String upper = sql.toUpperCase(Locale.ROOT);
        int idx = upper.lastIndexOf("ORDER BY");
        if (idx < 0) {
            String dir = normalizeDirection(direction);
            return sql + "\nORDER BY " + column + " " + dir;
        }
        int lineEnd = sql.indexOf('\n', idx);
        String dir = normalizeDirection(direction);
        String replacement = "ORDER BY " + column + " " + dir;
        if (lineEnd < 0) {
            return sql.substring(0, idx) + replacement;
        }
        return sql.substring(0, idx) + replacement + sql.substring(lineEnd);
    }

    private static String replaceOrAppendLimit(String sql, int limit) {
        String upper = sql.toUpperCase(Locale.ROOT);
        int idx = upper.lastIndexOf("LIMIT");
        if (idx < 0) {
            return sql + "\nLIMIT " + limit;
        }
        int lineEnd = sql.indexOf('\n', idx);
        String replacement = "LIMIT " + limit;
        if (lineEnd < 0) {
            return sql.substring(0, idx) + replacement;
        }
        return sql.substring(0, idx) + replacement + sql.substring(lineEnd);
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return "DESC";
        }
        return direction.toUpperCase(Locale.ROOT);
    }
}
