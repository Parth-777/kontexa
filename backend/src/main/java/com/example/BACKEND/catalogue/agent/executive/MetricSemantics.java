package com.example.BACKEND.catalogue.agent.executive;

/**
 * Machine-readable metric display semantics from the catalogue.
 *
 * {@code canonical = rawWarehouseValue * scaleToCanonical}
 * Then format using {@code displayUnit}.
 *
 * When scale/unit are absent, Insights must fail closed for unit-sensitive display
 * (no invented currency / compact $B/$M/$K formatting).
 */
public record MetricSemantics(
        String displayUnit,
        Double scaleToCanonical,
        String grainLabel
) {
    public static final String UNIT_CURRENCY = "currency";
    public static final String UNIT_PERCENT = "percent";
    public static final String UNIT_COUNT = "count";
    public static final String UNIT_NUMBER = "number";
    public static final String UNIT_RAW = "raw";

    public static MetricSemantics unknown() {
        return new MetricSemantics(null, null, null);
    }

    public static MetricSemantics of(String displayUnit, Double scaleToCanonical, String grainLabel) {
        return new MetricSemantics(
                blankToNull(displayUnit),
                scaleToCanonical,
                blankToNull(grainLabel));
    }

    public boolean hasTrustworthyScale() {
        return scaleToCanonical != null
                && !scaleToCanonical.isNaN()
                && !scaleToCanonical.isInfinite()
                && scaleToCanonical != 0.0;
    }

    public boolean isCurrency() {
        if (displayUnit == null) return false;
        String u = displayUnit.trim().toLowerCase();
        return UNIT_CURRENCY.equals(u) || u.equals("usd") || u.equals("money") || u.equals("dollar");
    }

    public boolean isPercent() {
        return displayUnit != null && UNIT_PERCENT.equalsIgnoreCase(displayUnit.trim());
    }

    public boolean isCount() {
        return displayUnit != null && UNIT_COUNT.equalsIgnoreCase(displayUnit.trim());
    }

    /** True when currency (or compact money) formatting is allowed. */
    public boolean allowsCurrencyDisplay() {
        return isCurrency() && hasTrustworthyScale();
    }

    public double toCanonical(double raw) {
        if (!hasTrustworthyScale()) return raw;
        return raw * scaleToCanonical;
    }

    public String resolvedGrainLabel() {
        if (grainLabel != null && !grainLabel.isBlank()) return grainLabel.trim();
        return "records";
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) return null;
        return s.trim();
    }
}
