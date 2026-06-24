package com.example.BACKEND.catalogue.decision.presentation.executive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Central guard for invalid numeric values in executive presentation output.
 */
public final class PresentationValueSanitizer {

    public static final String DASH = "—";
    public static final String NOT_AVAILABLE = "Not available";

    private static final Pattern INVALID_TOKEN = Pattern.compile(
            "(?i)\\b(nan|-?infinity|undefined)\\b|nan%");

    private PresentationValueSanitizer() {}

    public static boolean isUnavailable(Double value) {
        return value == null || isUnavailable(value.doubleValue());
    }

    public static boolean isUnavailable(double value) {
        return Double.isNaN(value) || Double.isInfinite(value);
    }

    public static String numericRaw(double value) {
        return isUnavailable(value) ? "" : String.valueOf(value);
    }

    /** KPI cards and table cells. */
    public static String sanitizeDisplayText(String text) {
        return sanitizeDisplayText(text, false);
    }

    /** Tooltips use explicit unavailable wording. */
    public static String sanitizeDisplayText(String text, boolean tooltip) {
        if (text == null || text.isBlank()) {
            return tooltip ? NOT_AVAILABLE : DASH;
        }
        String trimmed = text.trim();
        if (INVALID_TOKEN.matcher(trimmed).find()) {
            return tooltip ? NOT_AVAILABLE : DASH;
        }
        return text;
    }

    public static String contributionUnavailableMessage() {
        return "Contribution percentage unavailable because denominator could not be computed.";
    }

    public static String remainingUnavailableMessage() {
        return "Remaining value unavailable because total metric is missing.";
    }

    public static String percentDifferenceUnavailableMessage() {
        return "Percent difference unavailable because the baseline metric is zero or missing.";
    }

    public static String growthUnavailableMessage() {
        return "Growth rate unavailable because the prior period value is zero or missing.";
    }

    public static String varianceUnavailableMessage() {
        return "Variance metrics unavailable because the sample could not be computed.";
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizePresentationMap(Map<String, Object> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeObject(entry.getValue()));
        }
        return sanitized;
    }

    private static Object sanitizeObject(Object value) {
        if (value instanceof String text) {
            return sanitizeDisplayText(text);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(sanitizeObject(item));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), sanitizeObject(entry.getValue()));
            }
            return out;
        }
        return value;
    }
}
