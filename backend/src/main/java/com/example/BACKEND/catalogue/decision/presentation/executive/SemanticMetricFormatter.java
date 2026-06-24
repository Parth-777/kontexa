package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for executive number formatting (KPIs, tables, charts).
 */
@Component
public class SemanticMetricFormatter {

    private static final Set<String> KNOWN_FORMATS =
            Set.of("currency", "percent", "number", "text", "date");

    public String asMultiple(double ratio) {
        if (ratio <= 0 || Double.isNaN(ratio) || Double.isInfinite(ratio)) return "";
        if (ratio >= 10) return String.format(Locale.ROOT, "%.0f×", ratio);
        if (ratio >= 2) return String.format(Locale.ROOT, "%.1f×", ratio);
        return String.format(Locale.ROOT, "%.2f×", ratio);
    }

    /** Converts a percent delta (e.g. 750) into a relative multiple phrase fragment (8.5×). */
    public String percentDeltaAsMultiple(double deltaPct) {
        double multiple = 1.0 + (Math.abs(deltaPct) / 100.0);
        return asMultiple(multiple);
    }

    public String asSharePct(double pct) {
        if (PresentationValueSanitizer.isUnavailable(pct)) {
            return PresentationValueSanitizer.DASH;
        }
        if (pct >= 100) return String.format(Locale.ROOT, "%.0f%%", pct);
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }

    public String asCurrency(double amount) {
        if (PresentationValueSanitizer.isUnavailable(amount)) {
            return PresentationValueSanitizer.DASH;
        }
        double abs = Math.abs(amount);
        String sign = amount < 0 ? "-" : "";
        if (abs >= 1_000_000_000) {
            double b = abs / 1_000_000_000;
            return sign + "$" + String.format(Locale.ROOT, b >= 10 ? "%.1fB" : "%.2fB", b);
        }
        if (abs >= 1_000_000) {
            double m = abs / 1_000_000;
            return sign + "$" + String.format(Locale.ROOT, m >= 100 ? "%.0fM" : "%.1fM", m);
        }
        if (abs >= 1_000) {
            return sign + "$" + String.format(Locale.ROOT, "%.1fK", abs / 1_000);
        }
        if (abs >= 100) {
            return sign + "$" + String.format(Locale.ROOT, "%.0f", abs);
        }
        return sign + "$" + String.format(Locale.ROOT, "%.2f", abs);
    }

    public String formatPercentValue(double value) {
        if (PresentationValueSanitizer.isUnavailable(value)) {
            return PresentationValueSanitizer.DASH;
        }
        if (value > 0 && value <= 1.0) {
            return asSharePct(value * 100.0);
        }
        return asSharePct(value);
    }

    public String detectFormat(String columnKey) {
        if (columnKey == null || columnKey.isBlank()) {
            return "number";
        }
        if (KNOWN_FORMATS.contains(columnKey.toLowerCase(Locale.ROOT))) {
            return columnKey.toLowerCase(Locale.ROOT);
        }
        String key = columnKey.toLowerCase(Locale.ROOT);
        if (key.contains("date") || key.contains("time") || key.contains("timestamp")
                || key.endsWith("_day") || key.endsWith("_month") || key.endsWith("_year")) {
            return "date";
        }
        if (key.contains("revenue") || key.contains("amount") || key.contains("fare")
                || key.contains("cost") || key.contains("price") || key.contains("fee")
                || key.contains("tip") || key.contains("total") || key.contains("sales")) {
            return "currency";
        }
        if (key.contains("pct") || key.contains("percent") || key.contains("share")
                || key.contains("rate") || key.contains("_ratio") || key.endsWith("ratio")) {
            return "percent";
        }
        if (key.equals("count") || key.endsWith("_count") || key.startsWith("count_")
                || key.contains("id") || key.contains("rank")
                || key.contains("coefficient") || key.contains("distance")
                || key.contains("hours") || key.contains("quantity")) {
            return "number";
        }
        return "text";
    }

    public String formatForDisplay(double value, String formatOrColumnKey) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "—";
        }
        String format = KNOWN_FORMATS.contains(formatOrColumnKey != null
                ? formatOrColumnKey.toLowerCase(Locale.ROOT) : "")
                ? formatOrColumnKey.toLowerCase(Locale.ROOT)
                : detectFormat(formatOrColumnKey);
        return switch (format) {
            case "currency" -> asCurrency(value);
            case "percent" -> formatPercentValue(value);
            case "number" -> compactNumber(value);
            default -> compactNumber(value);
        };
    }

    public String formatValue(double value, String metricKey) {
        return formatForDisplay(value, metricKey);
    }

    public String compactNumber(double n) {
        if (PresentationValueSanitizer.isUnavailable(n)) {
            return PresentationValueSanitizer.DASH;
        }
        double abs = Math.abs(n);
        if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB", n / 1_000_000_000);
        if (abs >= 1_000_000) return String.format(Locale.ROOT, "%.0fM", n / 1_000_000);
        if (abs >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000);
        if (abs >= 100 || abs == Math.rint(abs)) return String.format(Locale.ROOT, "%.0f", n);
        return String.format(Locale.ROOT, "%.1f", n);
    }
}
