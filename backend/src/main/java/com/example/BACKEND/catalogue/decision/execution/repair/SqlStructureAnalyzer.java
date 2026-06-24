package com.example.BACKEND.catalogue.decision.execution.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight SQL structure extraction for execution diagnostics.
 */
final class SqlStructureAnalyzer {

    private static final Pattern SELECT_COLS = Pattern.compile(
            "(?is)SELECT\\s+(.+?)\\s+FROM\\s+");
    private static final Pattern GROUP_BY = Pattern.compile(
            "(?is)GROUP\\s+BY\\s+(.+?)(\\s+ORDER\\s+BY|\\s+HAVING|\\s+LIMIT|$)");
    private static final Pattern WHERE = Pattern.compile(
            "(?is)WHERE\\s+(.+?)(\\s+GROUP\\s+BY|\\s+ORDER\\s+BY|\\s+HAVING|\\s+LIMIT|$)");
    private static final Pattern AGG = Pattern.compile(
            "(?i)(SUM|AVG|COUNT|MIN|MAX)\\s*\\([^)]+\\)");

    private SqlStructureAnalyzer() {}

    static List<String> selectedColumns(String sql) {
        if (sql == null) return List.of();
        Matcher m = SELECT_COLS.matcher(sql.trim());
        if (!m.find()) return List.of();
        return splitTopLevel(m.group(1).trim());
    }

    static List<String> groupByColumns(String sql) {
        if (sql == null) return List.of();
        Matcher m = GROUP_BY.matcher(sql.trim());
        if (!m.find()) return List.of();
        return splitTopLevel(m.group(1).trim());
    }

    static String whereClause(String sql) {
        if (sql == null) return "";
        Matcher m = WHERE.matcher(sql.trim());
        return m.find() ? m.group(1).trim() : "";
    }

    static List<String> aggregationExpressions(String sql) {
        if (sql == null) return List.of();
        List<String> aggs = new ArrayList<>();
        Matcher m = AGG.matcher(sql);
        while (m.find()) aggs.add(m.group().trim());
        return aggs;
    }

    static boolean hasBucketCase(String sql) {
        return sql != null && sql.toUpperCase(Locale.ROOT).contains("CASE");
    }

    private static List<String> splitTopLevel(String clause) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : clause.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')' && depth > 0) depth--;
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) parts.add(current.toString().trim());
        return parts.stream().filter(s -> !s.isBlank()).toList();
    }
}
