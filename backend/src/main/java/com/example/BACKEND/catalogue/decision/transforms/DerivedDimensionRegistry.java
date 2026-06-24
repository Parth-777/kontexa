package com.example.BACKEND.catalogue.decision.transforms;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Maps business dimension keys and question phrases to semantic derivation concepts.
 */
@Component
public class DerivedDimensionRegistry {

    public Optional<SemanticConcept> resolveConcept(String dimensionKey, String question) {
        if (dimensionKey != null && !dimensionKey.isBlank()) {
            Optional<SemanticConcept> fromKey = fromKey(dimensionKey);
            if (fromKey.isPresent()) return fromKey;
        }
        if (question != null) {
            return fromQuestion(question);
        }
        return Optional.empty();
    }

    public Optional<SemanticConcept> fromKey(String dimensionKey) {
        if (dimensionKey == null) return Optional.empty();
        String lower = dimensionKey.toLowerCase(Locale.ROOT);
        if (lower.equals("weekend_flag") || lower.contains("weekend")) return Optional.of(SemanticConcept.WEEKEND_DAY);
        if (lower.equals("weekday")) return Optional.of(SemanticConcept.WEEKDAY);
        if (lower.contains("hour") || lower.equals("pickup_hour") || lower.equals("hour_of_day")) {
            return Optional.of(SemanticConcept.HOUR_OF_DAY);
        }
        if (lower.contains("month")) return Optional.of(SemanticConcept.MONTH);
        if (lower.contains("quarter")) return Optional.of(SemanticConcept.QUARTER);
        if (lower.contains("year") && !lower.contains("hour")) return Optional.of(SemanticConcept.YEAR);
        if (lower.contains("week") && !lower.contains("weekend")) return Optional.of(SemanticConcept.WEEK);
        if (lower.contains("airport")) return Optional.of(SemanticConcept.AIRPORT_RIDE);
        if (lower.contains("trip_distance") || lower.equals("distance")
                || lower.contains("distance_bucket")) {
            return Optional.of(SemanticConcept.TRIP_DISTANCE_BUCKET);
        }
        if (lower.contains("fare")) return Optional.of(SemanticConcept.FARE_BUCKET);
        if (lower.contains("tip")) return Optional.of(SemanticConcept.TIP_BUCKET);
        return Optional.empty();
    }

    public Optional<SemanticConcept> fromQuestion(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("weekend")) return Optional.of(SemanticConcept.WEEKEND_DAY);
        if (q.contains("hourly") || q.contains("hour of day") || q.contains("by hour")) {
            return Optional.of(SemanticConcept.HOUR_OF_DAY);
        }
        if (q.contains("monthly") || q.contains("by month")) return Optional.of(SemanticConcept.MONTH);
        if (q.contains("airport")) return Optional.of(SemanticConcept.AIRPORT_RIDE);
        if (q.contains("trip distance") || (q.contains("distance") && q.contains("mile"))) {
            return Optional.of(SemanticConcept.TRIP_DISTANCE_BUCKET);
        }
        return Optional.empty();
    }

    public boolean isTemporal(SemanticConcept concept) {
        return switch (concept) {
            case WEEKEND_DAY, WEEKDAY, HOUR_OF_DAY, DAY_OF_WEEK, WEEK, MONTH, QUARTER, YEAR -> true;
            default -> false;
        };
    }

    public boolean isNumericBucket(SemanticConcept concept) {
        return switch (concept) {
            case TRIP_DISTANCE_BUCKET, FARE_BUCKET, TIP_BUCKET -> true;
            default -> false;
        };
    }
}
