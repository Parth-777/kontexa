package com.example.BACKEND.catalogue.decision.exploration;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fallback phrase → column/grouping mappings when strict parsing is uncertain.
 */
@Component
public class SemanticFallbackDictionary {

    public record FallbackMapping(
            String phrase,
            String metricColumn,
            String dimensionColumn,
            String groupingColumn,
            String label
    ) {}

    private static final List<FallbackMapping> MAPPINGS = List.of(
            map("weekend rides", "total_amount", "weekend_flag", "weekend_flag", "Weekend rides"),
            map("weekend ride", "total_amount", "weekend_flag", "weekend_flag", "Weekend rides"),
            map("weekend", "total_amount", "weekend_flag", "weekend_flag", "Weekend"),
            map("weekday", "total_amount", "weekday", "weekday", "Weekday"),
            map("trip distance", "total_amount", "trip_distance", "trip_distance_bucket", "Trip distance"),
            map("distance", "total_amount", "trip_distance", "trip_distance_bucket", "Trip distance"),
            map("tip amount", "tip_amount", "total_amount", null, "Tips"),
            map("tips", "tip_amount", "total_amount", null, "Tips"),
            map("revenue", "total_amount", null, null, "Revenue"),
            map("pickup zone", "total_amount", "pickup_zone", "pickup_zone_bucket", "Pickup zone"),
            map("pickup zones", "total_amount", "pickup_zone", "pickup_zone_bucket", "Pickup zone")
    );

    public Optional<FallbackMapping> match(String question) {
        if (question == null) return Optional.empty();
        String padded = " " + question.toLowerCase(Locale.ROOT) + " ";
        for (FallbackMapping m : sorted()) {
            if (phraseMatches(padded, m.phrase())) return Optional.of(m);
        }
        return Optional.empty();
    }

    public List<FallbackMapping> matchAll(String question) {
        if (question == null) return List.of();
        String padded = " " + question.toLowerCase(Locale.ROOT) + " ";
        return sorted().stream().filter(m -> phraseMatches(padded, m.phrase())).toList();
    }

    private boolean phraseMatches(String paddedQuestion, String phrase) {
        if (phrase.contains(" ")) {
            return paddedQuestion.contains(" " + phrase + " ");
        }
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(phrase) + "\\b")
                .matcher(paddedQuestion).find();
    }

    private List<FallbackMapping> sorted() {
        return MAPPINGS.stream()
                .sorted((a, b) -> Integer.compare(b.phrase().length(), a.phrase().length()))
                .toList();
    }

    private static FallbackMapping map(
            String phrase, String metric, String dim, String grouping, String label
    ) {
        return new FallbackMapping(phrase, metric, dim, grouping, label);
    }
}
