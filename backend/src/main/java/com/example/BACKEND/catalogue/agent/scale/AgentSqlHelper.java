package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.schema.SqlFieldReferenceEmitter;

/**
 * Shared SQL fragments for scale-aware Insights agents.
 */
public final class AgentSqlHelper {

    private AgentSqlHelper() {}

    public static String windowClause(TableContext ctx) {
        if (ctx.window() == null || !ctx.window().active() || ctx.window().dateColumn() == null) {
            return "";
        }
        String dateRef = qualify(ctx.window().dateColumn(), ctx.provider());
        return ctx.window().whereClause(dateRef, ctx.provider());
    }

    /** Use when SQL already contains WHERE — returns " AND date >= ..." or "". */
    public static String windowAndClause(TableContext ctx) {
        String clause = windowClause(ctx);
        if (clause.isBlank()) return "";
        return clause.replaceFirst(" (?i)WHERE ", " AND ");
    }

    /**
     * Qualify a catalogue leaf path for the warehouse dialect.
     * Nested paths must be quoted per segment ({@code `totals`.`visits`}),
     * never as a single identifier ({@code `totals.visits`}).
     */
    public static String qualify(String col, String provider) {
        if (col == null || col.isBlank()) return col;
        if ("bigquery".equalsIgnoreCase(provider)) {
            return SqlFieldReferenceEmitter.emitQuotedReference(col);
        }
        if ("snowflake".equalsIgnoreCase(provider)) {
            return quoteSegments(col, "\"");
        }
        return col;
    }

    private static String quoteSegments(String path, String quote) {
        String[] parts = path.trim().split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(quote).append(parts[i].trim()).append(quote);
        }
        return sb.toString();
    }

    public static String qualifiedTableRef(TableContext ctx) {
        return qualifyTableRef(ctx.tableRef(), ctx.provider());
    }

    public static String qualifyTableRef(String tableRef, String provider) {
        if (tableRef == null || tableRef.isBlank()) return tableRef;
        if (!"bigquery".equalsIgnoreCase(provider)) return tableRef;
        if (tableRef.startsWith("`")) return tableRef;
        if (tableRef.contains(".")) {
            String[] parts = tableRef.split("\\.");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append('.');
                sb.append('`').append(parts[i].trim()).append('`');
            }
            return sb.toString();
        }
        return "`" + tableRef.trim() + "`";
    }

    /**
     * Normalizes a temporal column to DATE across common warehouse encodings:
     * DATE/TIMESTAMP types, ISO strings, compact YYYYMMDD strings, and epoch INT64.
     */
    public static String asDateExpr(String dateColumnRef, String provider) {
        if ("bigquery".equalsIgnoreCase(provider)) {
            // Avoid SAFE_CAST(x AS DATE) — BigQuery dry-run rejects INT64→DATE even via SAFE_CAST.
            return "DATE(COALESCE("
                    + "SAFE_CAST(SAFE_CAST(" + dateColumnRef + " AS TIMESTAMP) AS DATE), "
                    + "SAFE.PARSE_DATE('%Y-%m-%d', SUBSTR(TRIM(CAST(" + dateColumnRef + " AS STRING)), 1, 10)), "
                    + "SAFE.PARSE_DATE('%Y%m%d', SUBSTR(REGEXP_REPLACE(CAST("
                    + dateColumnRef + " AS STRING), r'[^0-9]', ''), 1, 8)), "
                    + "DATE(TIMESTAMP_SECONDS(SAFE_CAST(" + dateColumnRef + " AS INT64))), "
                    + "DATE(TIMESTAMP_MILLIS(SAFE_CAST(" + dateColumnRef + " AS INT64)))"
                    + "))";
        }
        if ("snowflake".equalsIgnoreCase(provider)) {
            return "COALESCE("
                    + "TRY_TO_DATE(TO_VARCHAR(" + dateColumnRef + ")), "
                    + "TRY_TO_DATE(TO_VARCHAR(" + dateColumnRef + "), 'YYYYMMDD'), "
                    + "DATE(TO_TIMESTAMP(" + dateColumnRef + "))"
                    + ")";
        }
        return "CAST(" + dateColumnRef + " AS DATE)";
    }

    /** Normalizes a date/timestamp column to a calendar day for grouping. */
    public static String dateDayExpr(String dateColumnRef, String provider) {
        return asDateExpr(dateColumnRef, provider);
    }

    /** Provider-specific DATE_TRUNC (month). Safe for STRING date columns. */
    public static String dateTruncMonth(String dateColumnRef, String provider) {
        return dateTrunc(dateColumnRef, "MONTH", provider);
    }

    /** Provider-specific DATE_TRUNC. part is MONTH, WEEK, DAY, etc. */
    public static String dateTrunc(String dateColumnRef, String part, String provider) {
        String dateExpr = asDateExpr(dateColumnRef, provider);
        if ("bigquery".equalsIgnoreCase(provider)) {
            return "DATE_TRUNC(" + dateExpr + ", " + part + ")";
        }
        if ("snowflake".equalsIgnoreCase(provider)) {
            return "DATE_TRUNC('" + part + "', " + dateExpr + ")";
        }
        return "DATE_TRUNC('" + part.toLowerCase() + "', " + dateExpr + ")";
    }

    /**
     * Bounds of the N most recent distinct dates present in the table (data-anchored, not "today − N").
     */
    public static String recentDatesBoundsSql(TableContext ctx, String dateCol, int recentDateCount) {
        String dateRef = qualify(dateCol, ctx.provider());
        String dayExpr = dateDayExpr(dateRef, ctx.provider());
        String tableRef = qualifiedTableRef(ctx);
        return String.format(
                "SELECT MIN(d) AS window_start, MAX(d) AS window_end FROM ("
                        + "SELECT %s AS d FROM %s WHERE %s IS NOT NULL GROUP BY d ORDER BY d DESC LIMIT %d)",
                dayExpr, tableRef, dayExpr, recentDateCount);
    }

    /** FROM table + date window + optional AND predicates (avoids duplicate WHERE). */
    public static String fromWithPredicates(TableContext ctx, String andPredicate) {
        String base = qualifiedTableRef(ctx) + windowClause(ctx);
        if (andPredicate == null || andPredicate.isBlank()) return base;
        if (base.toUpperCase().contains(" WHERE ")) {
            return base + " AND " + andPredicate;
        }
        return base + " WHERE " + andPredicate;
    }
}
