package com.example.BACKEND.catalogue.decision.grounding;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves internal aliases and observation keys to business-readable labels.
 * Never exposes FLAG.VAL, OBS-N, or raw warehouse aliases in UI-facing text.
 */
@Component
public class PresentationLabelResolver {

    private static final Pattern INTERNAL_KEY = Pattern.compile(
            "^(FLAG\\.|OBS-\\d+|efficiency_ratio|metric_value|segment_total|group_key|"
                    + "share_pct|entity_key|raw_|internal_|alias_).*$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CURRENCY_LIKE = Pattern.compile(
            "(fare|amount|revenue|total|payment|tip)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> DIMENSION_HINTS = Set.of(
            "distance", "trip", "hour", "day", "zone", "borough", "vendor", "payment_type",
            "category", "segment", "channel", "region", "month", "weekday", "period",
            "pickup", "dropoff", "location", "corridor", "range", "bucket"
    );

    private static final Set<String> METRIC_HINTS = Set.of(
            "revenue", "fare", "amount", "total", "count", "volume", "trips", "avg", "sum",
            "efficiency", "rate", "value"
    );

    private static final Map<String, String> KNOWN_LABELS = Map.ofEntries(
            Map.entry("trip_distance", "Trip Distance"),
            Map.entry("total_amount", "Total Revenue"),
            Map.entry("fare_amount", "Base Fare Revenue"),
            Map.entry("tip_amount", "Tips"),
            Map.entry("tolls_amount", "Tolls"),
            Map.entry("passenger_count", "Passenger Count"),
            Map.entry("trip_duration", "Trip Duration"),
            Map.entry("hour_of_day", "Hour of Day"),
            Map.entry("day_of_week", "Day of Week"),
            Map.entry("pickup_datetime", "Pickup Time"),
            Map.entry("dropoff_datetime", "Dropoff Time"),
            Map.entry("payment_type", "Payment Type"),
            Map.entry("vendor_id", "Vendor"),
            Map.entry("rate_code_id", "Rate Code"),
            Map.entry("pulocationid", "Pickup Zone"),
            Map.entry("dolocationid", "Dropoff Zone"),
            Map.entry("value", "Revenue"),
            Map.entry("metric_value", "Revenue"),
            Map.entry("segment_revenue", "Revenue"),
            Map.entry("route_revenue", "Revenue")
    );

    public String resolve(String raw) {
        if (raw == null || raw.isBlank()) return "Metric";
        String trimmed = raw.trim();

        String known = KNOWN_LABELS.get(trimmed.toLowerCase(Locale.ROOT));
        if (known != null) return known;

        if (INTERNAL_KEY.matcher(trimmed).matches()) {
            return inferFromContext(trimmed);
        }

        return toBusinessLabel(trimmed);
    }

    public SemanticFieldRef classify(String rawKey, MaterializationSpec.SpecType specType) {
        SemanticFieldKind kind = inferKind(rawKey, specType);
        return new SemanticFieldRef(rawKey, resolve(rawKey), kind);
    }

    public SemanticFieldRef classifyMetric(String rawKey) {
        return new SemanticFieldRef(rawKey, resolveMetric(rawKey), SemanticFieldKind.METRIC);
    }

    public SemanticFieldRef classifyDimension(String rawKey, MaterializationSpec.SpecType specType) {
        SemanticFieldKind kind = specType == MaterializationSpec.SpecType.DERIVED_TIME
                ? SemanticFieldKind.TEMPORAL_DIMENSION
                : SemanticFieldKind.CATEGORICAL_DIMENSION;
        return new SemanticFieldRef(rawKey, resolve(rawKey), kind);
    }

    public String resolveMetric(String raw) {
        String resolved = resolve(raw);
        if (isLikelyDimension(raw) && !isLikelyMetric(raw)) {
            return "Revenue";
        }
        return resolved;
    }

    public String resolveDimension(String raw) {
        String resolved = resolve(raw);
        if (isLikelyMetric(raw) && !isLikelyDimension(raw)) {
            return toBusinessLabel(raw) + " Segment";
        }
        return resolved;
    }

    public String resolveSegment(String raw) {
        if (raw == null || raw.isBlank()) return "segment";
        if (INTERNAL_KEY.matcher(raw).matches()) return "selected segment";
        return toBusinessLabel(raw);
    }

    public boolean isInternalKey(String key) {
        return key == null || key.isBlank() || INTERNAL_KEY.matcher(key.trim()).matches();
    }

    public boolean isLikelyDimension(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        return DIMENSION_HINTS.stream().anyMatch(lower::contains);
    }

    public boolean isLikelyMetric(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (CURRENCY_LIKE.matcher(lower).find()) return true;
        return METRIC_HINTS.stream().anyMatch(lower::contains);
    }

    private SemanticFieldKind inferKind(String rawKey, MaterializationSpec.SpecType specType) {
        if (specType == MaterializationSpec.SpecType.DERIVED_TIME) {
            return SemanticFieldKind.TEMPORAL_DIMENSION;
        }
        if (rawKey != null && rawKey.toLowerCase(Locale.ROOT).contains("id")) {
            return SemanticFieldKind.IDENTIFIER;
        }
        if (isLikelyMetric(rawKey) && !isLikelyDimension(rawKey)) {
            return SemanticFieldKind.METRIC;
        }
        return SemanticFieldKind.CATEGORICAL_DIMENSION;
    }

    private String inferFromContext(String internal) {
        String lower = internal.toLowerCase(Locale.ROOT);
        if (lower.contains("revenue") || lower.contains("fare") || lower.contains("amount")) {
            return "Revenue";
        }
        if (lower.contains("share") || lower.contains("pct")) return "Share";
        if (lower.contains("count") || lower.contains("volume")) return "Volume";
        return "Value";
    }

    private String toBusinessLabel(String raw) {
        String cleaned = raw
                .replaceAll("^(FLAG\\.|OBS-\\d+)", "")
                .replaceAll("[_\\-.]+", " ")
                .trim();
        if (cleaned.isBlank()) return "Metric";

        StringBuilder sb = new StringBuilder();
        for (String word : cleaned.split("\\s+")) {
            if (word.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            if (word.length() > 1) sb.append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
