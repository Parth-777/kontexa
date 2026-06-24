package com.example.BACKEND.catalogue.semantic.canonical;

import java.util.Locale;

/**
 * Normalizes SQL text for shadow comparison.
 */
public final class CanonicalSqlDiffComparator {

    private CanonicalSqlDiffComparator() {}

    public static boolean sqlEquals(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
