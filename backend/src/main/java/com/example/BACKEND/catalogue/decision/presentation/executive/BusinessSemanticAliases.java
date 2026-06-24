package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Maps warehouse metric keys to executive business labels.
 */
@Component
public class BusinessSemanticAliases {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("total_amount", "Total Revenue"),
            Map.entry("fare_amount", "Base Fare Revenue"),
            Map.entry("tip_amount", "Tips"),
            Map.entry("tolls_amount", "Tolls & Surcharges"),
            Map.entry("extra", "Surcharges"),
            Map.entry("extra_surcharge", "Surcharges"),
            Map.entry("improvement_surcharge", "Surcharges"),
            Map.entry("congestion_surcharge", "Surcharges"),
            Map.entry("revenue_per_mile", "Revenue per Mile"),
            Map.entry("revenue_per_trip", "Revenue per Trip"),
            Map.entry("trip_distance", "Trip Distance"),
            Map.entry("passenger_count", "Passengers"),
            Map.entry("volume", "Trip Count"),
            Map.entry("payment_type", "Payment Type"),
            Map.entry("pickup_hour", "Hour of Day"),
            Map.entry("pickup_zone", "Pickup Zone")
    );

    public String resolve(String raw) {
        if (raw == null || raw.isBlank()) return "Value";
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(key, toTitleCase(raw));
    }

    public String resolveSegment(String raw) {
        if (raw == null || raw.isBlank()) return "this segment";
        if (raw.matches("(?i).*[–\\-].*mile.*|.*km.*")) return raw;
        if (raw.matches("\\d+-\\d+")) return raw.replace('-', '–') + " miles";
        if (raw.matches("\\d+\\+")) return raw + " miles";
        return toTitleCase(raw.replace('_', ' '));
    }

    private String toTitleCase(String raw) {
        String cleaned = raw.replaceAll("[_\\-.]+", " ").trim();
        if (cleaned.isBlank()) return "Value";
        StringBuilder sb = new StringBuilder();
        for (String word : cleaned.split("\\s+")) {
            if (word.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) sb.append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
