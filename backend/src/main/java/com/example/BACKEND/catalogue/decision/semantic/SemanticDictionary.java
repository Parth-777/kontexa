package com.example.BACKEND.catalogue.decision.semantic;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NYC taxi semantic dictionary — maps business phrases to warehouse columns.
 */
@Component
public class SemanticDictionary {

    public record DictionaryEntry(
            String phrase,
            String columnKey,
            String label,
            EntityKind kind
    ) {}

    private static final List<DictionaryEntry> NYC_TAXI = List.of(
            entry("tip amount", "tip_amount", "Tips", EntityKind.METRIC),
            entry("tips", "tip_amount", "Tips", EntityKind.METRIC),
            entry("total revenue", "total_amount", "Total Revenue", EntityKind.METRIC),
            entry("total amount", "total_amount", "Total Revenue", EntityKind.METRIC),
            entry("revenue", "total_amount", "Total Revenue", EntityKind.METRIC),
            entry("base fare", "fare_amount", "Base Fare Revenue", EntityKind.METRIC),
            entry("base fare revenue", "fare_amount", "Base Fare Revenue", EntityKind.METRIC),
            entry("fare amount", "fare_amount", "Base Fare Revenue", EntityKind.METRIC),
            entry("fare", "fare_amount", "Base Fare Revenue", EntityKind.METRIC),
            entry("tolls", "tolls_amount", "Tolls", EntityKind.METRIC),
            entry("trip distance", "trip_distance", "Trip Distance", EntityKind.DIMENSION),
            entry("distance", "trip_distance", "Trip Distance", EntityKind.DIMENSION),
            entry("mile", "trip_distance", "Trip Distance", EntityKind.DIMENSION),
            entry("miles", "trip_distance", "Trip Distance", EntityKind.DIMENSION),
            entry("hour", "pickup_hour", "Hour of Day", EntityKind.TEMPORAL_DIMENSION),
            entry("pickup hour", "pickup_hour", "Hour of Day", EntityKind.TEMPORAL_DIMENSION),
            entry("hour of day", "pickup_hour", "Hour of Day", EntityKind.TEMPORAL_DIMENSION),
            entry("pickup zone", "pickup_zone", "Pickup Zone", EntityKind.DIMENSION),
            entry("pickup zones", "pickup_zone", "Pickup Zone", EntityKind.DIMENSION),
            entry("trip distances", "trip_distance", "Trip Distance", EntityKind.DIMENSION),
            entry("airport rides", "airport_flag", "Airport", EntityKind.DIMENSION),
            entry("airport ride", "airport_flag", "Airport", EntityKind.DIMENSION),
            entry("airport", "airport_flag", "Airport", EntityKind.DIMENSION),
            entry("weekend rides", "weekend_flag", "Weekend", EntityKind.DIMENSION),
            entry("weekend ride", "weekend_flag", "Weekend", EntityKind.DIMENSION),
            entry("weekend", "weekend_flag", "Weekend", EntityKind.DIMENSION),
            entry("weekday", "weekday", "Weekday", EntityKind.DIMENSION),
            entry("dropoff zone", "dropoff_zone", "Dropoff Zone", EntityKind.DIMENSION),
            entry("zone", "pickup_zone", "Pickup Zone", EntityKind.DIMENSION),
            entry("payment type", "payment_type", "Payment Type", EntityKind.DIMENSION),
            entry("vendor", "vendor_id", "Vendor", EntityKind.DIMENSION),
            entry("passenger count", "passenger_count", "Passengers", EntityKind.METRIC),
            entry("trip count", "volume", "Trip Count", EntityKind.METRIC),
            entry("revenue per mile", "revenue_per_mile", "Revenue per Mile", EntityKind.DERIVED_METRIC),
            entry("revenue per trip", "revenue_per_trip", "Revenue per Trip", EntityKind.DERIVED_METRIC)
    );

    public List<DictionaryEntry> entries() {
        return NYC_TAXI;
    }

    public List<ResolvedEntity> matchAll(String question) {
        if (question == null || question.isBlank()) return List.of();
        String q = " " + question.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ") + " ";

        List<ResolvedEntity> matches = new ArrayList<>();
        for (DictionaryEntry e : sortedByPhraseLength()) {
            String needle = " " + e.phrase().toLowerCase(Locale.ROOT) + " ";
            if (q.contains(needle) || q.contains(e.phrase().toLowerCase(Locale.ROOT))) {
                double score = e.phrase().length() / (double) Math.max(1, question.length());
                matches.add(new ResolvedEntity(
                        e.phrase(), e.columnKey(), e.label(), e.kind(), Math.min(0.95, 0.6 + score)));
            }
        }
        return dedupe(matches);
    }

    public ResolvedEntity resolvePhrase(String phrase) {
        if (phrase == null) return null;
        String norm = phrase.toLowerCase(Locale.ROOT).trim();
        for (DictionaryEntry e : NYC_TAXI) {
            if (e.phrase().equals(norm)) {
                return new ResolvedEntity(e.phrase(), e.columnKey(), e.label(), e.kind(), 0.9);
            }
        }
        return null;
    }

    public String columnFor(String phrase) {
        ResolvedEntity e = resolvePhrase(phrase);
        return e != null ? e.columnKey() : null;
    }

    private List<DictionaryEntry> sortedByPhraseLength() {
        return NYC_TAXI.stream()
                .sorted(Comparator.comparingInt((DictionaryEntry e) -> e.phrase().length()).reversed())
                .toList();
    }

    private List<ResolvedEntity> dedupe(List<ResolvedEntity> matches) {
        Map<String, ResolvedEntity> byKey = new java.util.LinkedHashMap<>();
        for (ResolvedEntity m : matches) {
            byKey.merge(m.columnKey(), m, (a, b) -> a.matchScore() >= b.matchScore() ? a : b);
        }
        return new ArrayList<>(byKey.values());
    }

    private static DictionaryEntry entry(String phrase, String key, String label, EntityKind kind) {
        return new DictionaryEntry(phrase, key, label, kind);
    }
}
