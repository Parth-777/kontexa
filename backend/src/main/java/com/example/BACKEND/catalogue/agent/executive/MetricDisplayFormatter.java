package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.EnrichedColInfo;

import java.util.Locale;
import java.util.Map;

/**
 * Single Insights-path formatter: raw warehouse values → canonical → display.
 * Fail-closed for currency when catalogue scale/unit is missing.
 */
public final class MetricDisplayFormatter {

    private MetricDisplayFormatter() {}

    public static MetricSemantics semanticsOf(EnrichedColInfo info) {
        if (info == null) return MetricSemantics.unknown();
        return MetricSemantics.of(info.displayUnit(), info.scaleToCanonical(), info.grainLabel());
    }

    public static MetricSemantics semanticsOf(Map<String, EnrichedColInfo> enriched, String column) {
        if (enriched == null || column == null) return MetricSemantics.unknown();
        EnrichedColInfo info = enriched.get(column.toLowerCase(Locale.ROOT));
        if (info == null) {
            // nested paths sometimes stored with different casing / suffix
            for (Map.Entry<String, EnrichedColInfo> e : enriched.entrySet()) {
                if (e.getKey().equalsIgnoreCase(column)
                        || e.getKey().endsWith("." + column.toLowerCase(Locale.ROOT))) {
                    return semanticsOf(e.getValue());
                }
            }
            return MetricSemantics.unknown();
        }
        return semanticsOf(info);
    }

    public static double toCanonical(double raw, MetricSemantics sem) {
        if (sem == null) return raw;
        return sem.toCanonical(raw);
    }

    /**
     * Format a metric value for narrative/highlights/charts.
     * Currency without trustworthy scale → plain number (no $, B, M, K).
     */
    public static String formatMetric(Object raw, MetricSemantics sem) {
        double n = toDouble(raw);
        MetricSemantics s = sem == null ? MetricSemantics.unknown() : sem;

        if (s.isPercent()) {
            return ExecutiveVoice.formatPercent(n);
        }
        if (s.isCount()) {
            return formatCount(n, s.resolvedGrainLabel());
        }
        if (s.allowsCurrencyDisplay()) {
            return formatCurrency(s.toCanonical(n));
        }
        // Fail closed: known non-currency unit, or unknown — never invent $
        if (s.hasTrustworthyScale()) {
            return formatPlain(s.toCanonical(n));
        }
        return formatPlain(n);
    }

    /** Count / row volume — never currency-style. */
    public static String formatCount(Object raw) {
        return formatCount(toDouble(raw), "records");
    }

    public static String formatCount(Object raw, String grainLabel) {
        double n = toDouble(raw);
        String grain = (grainLabel == null || grainLabel.isBlank()) ? "records" : grainLabel.trim();
        String num;
        double abs = Math.abs(n);
        if (abs >= 1_000_000) num = String.format(Locale.US, "%.1fM", n / 1_000_000);
        else if (abs >= 10_000) num = String.format(Locale.US, "%.0fK", n / 1_000);
        else if (abs >= 100) num = String.format(Locale.US, "%.0f", n);
        else if (abs == Math.rint(n)) num = String.format(Locale.US, "%.0f", n);
        else num = String.format(Locale.US, "%.1f", n);
        return num;
    }

    public static String formatPercent(double pct) {
        return ExecutiveVoice.formatPercent(pct);
    }

    /** Chart value: scaled when trustworthy; otherwise raw. */
    public static double chartValue(Object raw, MetricSemantics sem) {
        double n = toDouble(raw);
        if (sem != null && sem.hasTrustworthyScale()) return sem.toCanonical(n);
        return n;
    }

    public static String chartValueFormat(MetricSemantics sem) {
        if (sem == null) return "number";
        if (sem.allowsCurrencyDisplay()) return "currency";
        if (sem.isPercent()) return "percent";
        return "number";
    }

    private static String formatCurrency(double canonical) {
        double abs = Math.abs(canonical);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "$%.2fB", canonical / 1_000_000_000);
        if (abs >= 1_000_000) return String.format(Locale.US, "$%.1fM", canonical / 1_000_000);
        if (abs >= 1_000) return String.format(Locale.US, "$%.2fK", canonical / 1_000);
        if (abs >= 100) return String.format(Locale.US, "$%.0f", canonical);
        if (abs >= 1) return String.format(Locale.US, "$%.2f", canonical);
        return String.format(Locale.US, "$%.2f", canonical);
    }

    private static String formatPlain(double n) {
        double abs = Math.abs(n);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "%.2fB", n / 1_000_000_000);
        if (abs >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000);
        if (abs >= 10_000) return String.format(Locale.US, "%.0fK", n / 1_000);
        if (abs >= 100) return String.format(Locale.US, "%.0f", n);
        if (abs >= 1) return String.format(Locale.US, "%.1f", n);
        return String.format(Locale.US, "%.2f", n);
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number num) return num.doubleValue();
        try {
            return Double.parseDouble(v.toString().replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
