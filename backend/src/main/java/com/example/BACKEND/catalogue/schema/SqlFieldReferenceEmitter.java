package com.example.BACKEND.catalogue.schema;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generic path-aware SQL field reference emission for nested and flat columns.
 */
public final class SqlFieldReferenceEmitter {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private SqlFieldReferenceEmitter() {
    }

    /**
     * Emits a warehouse field reference using dot notation (BigQuery standard SQL).
     */
    public static String emitFieldReference(String fieldPath) {
        validateFieldPath(fieldPath);
        return fieldPath.trim();
    }

    /**
     * Emits a backtick-quoted reference per path segment for sampling and reserved names.
     */
    public static String emitQuotedReference(String fieldPath) {
        validateFieldPath(fieldPath);
        String normalized = fieldPath.trim();
        if (!normalized.contains(".")) {
            return quoteSegment(normalized);
        }
        return Arrays.stream(normalized.split("\\."))
                .map(SqlFieldReferenceEmitter::quoteSegment)
                .collect(Collectors.joining("."));
    }

    /**
     * Emits a safe SQL alias (last path segment) for SELECT ... AS clauses.
     */
    public static String emitAlias(String fieldPath) {
        validateFieldPath(fieldPath);
        int dot = fieldPath.lastIndexOf('.');
        return dot >= 0 ? fieldPath.substring(dot + 1) : fieldPath;
    }

    public static void validateFieldPath(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("field path required");
        }
        String normalized = fieldPath.trim();
        if (normalized.contains(" ") || normalized.contains("`")) {
            throw new IllegalArgumentException("unsafe field path: " + fieldPath);
        }
        for (String segment : normalized.split("\\.")) {
            if (segment.isBlank() || !SAFE_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("unsafe field path segment in: " + fieldPath);
            }
        }
    }

    private static String quoteSegment(String segment) {
        return "`" + segment.replace("`", "") + "`";
    }
}
