package com.example.BACKEND.catalogue.semantic.canonical;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts planner-relevant structure from generated warehouse SQL for fidelity auditing.
 */
public final class SqlInspector {

    private static final Pattern CORR = Pattern.compile(
            "\\bCORR\\s*\\(\\s*([^,)]+)\\s*,\\s*([^)]+)\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_TRUNC = Pattern.compile(
            "\\bDATE_TRUNC\\s*\\(\\s*'([^']+)'\\s*,",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_BY = Pattern.compile(
            "\\bGROUP\\s+BY\\s+(.+?)(?:\\s+ORDER\\s+BY|\\s+LIMIT\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ORDER_BY = Pattern.compile(
            "\\bORDER\\s+BY\\s+([^\\n;]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile(
            "\\bLIMIT\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGG_ON_COLUMN = Pattern.compile(
            "\\b(SUM|AVG|COUNT|MIN|MAX)\\s*\\(\\s*([*\\w.]+)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private SqlInspector() {}

    public record ParsedSql(
            String metricAggregation,
            String metricColumn,
            List<String> groupByColumns,
            String dateTruncGrain,
            String orderColumn,
            String orderDirection,
            Integer limit,
            String corrLeft,
            String corrRight
    ) {}

    public static ParsedSql parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return new ParsedSql(null, null, List.of(), null, null, null, null, null, null);
        }
        String normalized = sql.replace('\n', ' ').replaceAll("\\s+", " ").trim();

        String corrLeft = null;
        String corrRight = null;
        Matcher corrMatcher = CORR.matcher(normalized);
        if (corrMatcher.find()) {
            corrLeft = normalizeIdentifier(corrMatcher.group(1));
            corrRight = normalizeIdentifier(corrMatcher.group(2));
        }

        String dateTruncGrain = null;
        Matcher grainMatcher = DATE_TRUNC.matcher(normalized);
        if (grainMatcher.find()) {
            dateTruncGrain = grainMatcher.group(1).toUpperCase(Locale.ROOT);
        }

        List<String> groupByColumns = new ArrayList<>();
        Matcher groupMatcher = GROUP_BY.matcher(normalized);
        if (groupMatcher.find()) {
            for (String part : groupMatcher.group(1).split(",")) {
                groupByColumns.add(normalizeGroupExpression(part.trim()));
            }
        }

        String orderColumn = null;
        String orderDirection = null;
        Matcher orderMatcher = ORDER_BY.matcher(normalized);
        if (orderMatcher.find()) {
            String clause = orderMatcher.group(1).trim();
            int limitIdx = clause.toUpperCase(Locale.ROOT).indexOf(" LIMIT ");
            if (limitIdx >= 0) {
                clause = clause.substring(0, limitIdx).trim();
            }
            String[] tokens = clause.split("\\s+");
            if (tokens.length > 0) {
                orderColumn = normalizeIdentifier(tokens[0]);
            }
            if (tokens.length > 1) {
                String direction = tokens[tokens.length - 1].toUpperCase(Locale.ROOT);
                if ("ASC".equals(direction) || "DESC".equals(direction)) {
                    orderDirection = direction;
                }
            }
        }

        Integer limit = null;
        Matcher limitMatcher = LIMIT.matcher(normalized);
        if (limitMatcher.find()) {
            try {
                limit = Integer.parseInt(limitMatcher.group(1));
            } catch (NumberFormatException ignored) {
                limit = null;
            }
        }

        String metricAggregation = null;
        String metricColumn = null;
        Matcher aggMatcher = AGG_ON_COLUMN.matcher(normalized);
        while (aggMatcher.find()) {
            String agg = aggMatcher.group(1).toUpperCase(Locale.ROOT);
            String col = normalizeIdentifier(aggMatcher.group(2));
            if ("*".equals(col)) {
                if (metricAggregation == null) {
                    metricAggregation = agg;
                    metricColumn = "*";
                }
                continue;
            }
            metricAggregation = agg;
            metricColumn = col;
        }

        return new ParsedSql(
                metricAggregation,
                metricColumn,
                List.copyOf(groupByColumns),
                dateTruncGrain,
                orderColumn,
                orderDirection,
                limit,
                corrLeft,
                corrRight);
    }

    public static String aggregationOnColumn(String sql, String column) {
        if (sql == null || column == null || column.isBlank()) {
            return null;
        }
        String normalized = sql.replace('\n', ' ').replaceAll("\\s+", " ");
        Pattern pattern = Pattern.compile(
                "\\b(SUM|AVG|COUNT|MIN|MAX)\\s*\\(\\s*" + Pattern.quote(column) + "\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(normalized);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String normalizeIdentifier(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        int asIdx = trimmed.toUpperCase(Locale.ROOT).lastIndexOf(" AS ");
        if (asIdx >= 0) {
            trimmed = trimmed.substring(0, asIdx).trim();
        }
        if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 1) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizeGroupExpression(String expression) {
        String normalized = normalizeIdentifier(expression);
        if (normalized == null) return null;
        Matcher grainMatcher = DATE_TRUNC.matcher(expression);
        if (grainMatcher.find()) {
            return grainMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        return normalized;
    }
}
