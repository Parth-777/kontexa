package com.example.BACKEND.catalogue.decision.semantic;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Intent pattern registry for natural-language analytical questions.
 */
public final class AnalyticalIntentPatterns {

    private AnalyticalIntentPatterns() {}

    public enum PatternKind {
        CONTRIBUTION,
        COMPOSITION,
        DIMENSION_IMPACT,
        RANKING,
        COMPARISON,
        TREND,
        ANOMALY
    }

    public record IntentMatch(PatternKind kind, AnalyticalIntentType intent, double confidence) {}

    private static final List<Pattern> CONTRIBUTION = List.of(
            Pattern.compile("(?i)how does .+ contribute to"),
            Pattern.compile("(?i)contribution of .+ to"),
            Pattern.compile("(?i)share of .+ in"),
            Pattern.compile("(?i)what share of .+ comes from"),
            Pattern.compile("(?i).+ contributes? to .+ revenue")
    );

    private static final List<Pattern> COMPOSITION = List.of(
            Pattern.compile("(?i)composition of"),
            Pattern.compile("(?i)breakdown of .+ revenue"),
            Pattern.compile("(?i)revenue mix"),
            Pattern.compile("(?i)what share of revenue comes from")
    );

    private static final List<Pattern> DIMENSION_IMPACT = List.of(
            Pattern.compile("(?i)how does .+ affect"),
            Pattern.compile("(?i)how does .+ impact"),
            Pattern.compile("(?i)how does .+ influence"),
            Pattern.compile("(?i).+ by .+"),
            Pattern.compile("(?i)which .+ (drive|generate|produce) (most|highest)"),
            Pattern.compile("(?i)revenue by"),
            Pattern.compile("(?i).+ across .+")
    );

    private static final List<Pattern> RANKING = List.of(
            Pattern.compile("(?i)which .+ (has|have) (the )?(highest|most|top|best)"),
            Pattern.compile("(?i)top .+ by"),
            Pattern.compile("(?i)rank .+ by"),
            Pattern.compile("(?i)leading .+")
    );

    private static final List<Pattern> COMPARISON = List.of(
            Pattern.compile("(?i)compare .+ (vs|versus|to|with|against)"),
            Pattern.compile("(?i)difference between"),
            Pattern.compile("(?i)how much (more|less)")
    );

    private static final List<Pattern> TREND = List.of(
            Pattern.compile("(?i)trend"),
            Pattern.compile("(?i)over time"),
            Pattern.compile("(?i)month over month"),
            Pattern.compile("(?i)week over week"),
            Pattern.compile("(?i)growth rate")
    );

    private static final List<Pattern> ANOMALY = List.of(
            Pattern.compile("(?i)anomal"),
            Pattern.compile("(?i)unusual"),
            Pattern.compile("(?i)outlier"),
            Pattern.compile("(?i)spike"),
            Pattern.compile("(?i)drop off")
    );

    public static IntentMatch detect(String question) {
        if (question == null || question.isBlank()) {
            return new IntentMatch(PatternKind.CONTRIBUTION, AnalyticalIntentType.GENERAL_ANALYSIS, 0.3);
        }
        String q = question.toLowerCase(Locale.ROOT);

        if (matchesAny(q, CONTRIBUTION)) {
            return new IntentMatch(PatternKind.CONTRIBUTION, AnalyticalIntentType.COMPOSITION, 0.92);
        }
        if (matchesAny(q, COMPOSITION)) {
            return new IntentMatch(PatternKind.COMPOSITION, AnalyticalIntentType.COMPOSITION, 0.9);
        }
        if (matchesAny(q, DIMENSION_IMPACT)) {
            return new IntentMatch(PatternKind.DIMENSION_IMPACT, AnalyticalIntentType.CONTRIBUTION, 0.88);
        }
        if (matchesAny(q, RANKING)) {
            return new IntentMatch(PatternKind.RANKING, AnalyticalIntentType.RANKING, 0.87);
        }
        if (matchesAny(q, COMPARISON)) {
            return new IntentMatch(PatternKind.COMPARISON, AnalyticalIntentType.COMPARISON, 0.85);
        }
        if (matchesAny(q, TREND)) {
            return new IntentMatch(PatternKind.TREND, AnalyticalIntentType.TREND_ANALYSIS, 0.84);
        }
        if (matchesAny(q, ANOMALY)) {
            return new IntentMatch(PatternKind.ANOMALY, AnalyticalIntentType.ANOMALY_DETECTION, 0.83);
        }
        return new IntentMatch(PatternKind.DIMENSION_IMPACT, AnalyticalIntentType.GENERAL_ANALYSIS, 0.4);
    }

    private static boolean matchesAny(String q, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(q).find()) return true;
        }
        return false;
    }
}
