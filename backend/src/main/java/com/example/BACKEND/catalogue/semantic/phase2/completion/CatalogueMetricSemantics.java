package com.example.BACKEND.catalogue.semantic.phase2.completion;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Catalogue-driven semantic scoring for contribution denominator selection.
 * Uses business descriptions as the primary signal; column names are secondary.
 */
final class CatalogueMetricSemantics {

    private static final int MIN_ACCEPT_SCORE = 10;

    private CatalogueMetricSemantics() {}

    enum MetricFamily {
        REVENUE,
        COST,
        PROFIT,
        ENVIRONMENTAL,
        GENERIC
    }

    static Optional<String> inferContributionDenominator(
            ApprovedCatalogueSnapshot catalogue,
            String numeratorColumn
    ) {
        if (catalogue == null || numeratorColumn == null || numeratorColumn.isBlank()) {
            return Optional.empty();
        }

        ApprovedCatalogueSnapshot.CatalogueColumn numerator = findMetric(catalogue, numeratorColumn).orElse(null);
        if (numerator == null) {
            return Optional.empty();
        }

        MetricFamily family = inferFamily(numerator);
        int bestScore = 0;
        String bestColumn = null;

        for (ApprovedCatalogueSnapshot.CatalogueColumn candidate : catalogue.columns()) {
            if (!isMetric(candidate)) {
                continue;
            }
            if (candidate.columnName().equalsIgnoreCase(numeratorColumn)) {
                continue;
            }

            int score = scoreDenominatorCandidate(numerator, candidate, family);
            if (score > bestScore) {
                bestScore = score;
                bestColumn = candidate.columnName();
            }
        }

        return bestScore >= MIN_ACCEPT_SCORE
                ? Optional.ofNullable(bestColumn)
                : Optional.empty();
    }

    static MetricFamily inferFamily(ApprovedCatalogueSnapshot.CatalogueColumn column) {
        String text = semanticText(column);
        if (containsAny(text, "co2", "carbon", "emission", "energy", "kwh", "footprint")) {
            return MetricFamily.ENVIRONMENTAL;
        }
        if (containsAny(text, "profit", "margin", "earnings")) {
            return MetricFamily.PROFIT;
        }
        if (containsAny(text, "cost", "expense", "spend", "maintenance")) {
            return MetricFamily.COST;
        }
        if (containsAny(text, "revenue", "sales", "fare", "fee", "tip", "amount", "charge", "price", "income")) {
            return MetricFamily.REVENUE;
        }
        return MetricFamily.GENERIC;
    }

    private static int scoreDenominatorCandidate(
            ApprovedCatalogueSnapshot.CatalogueColumn numerator,
            ApprovedCatalogueSnapshot.CatalogueColumn candidate,
            MetricFamily family
    ) {
        String description = normalize(numerator.description());
        String candidateDescription = normalize(candidate.description());
        String candidateName = normalize(candidate.columnName());

        int score = 0;

        score += phraseScore(candidateDescription, 5, totalConcepts(family));
        score += phraseScore(candidateDescription, 4, "total", "overall", "entire", "aggregate", "gross", "net");
        score += phraseScore(candidateDescription, 3, "including", "all trips", "all rides", "all records");

        if (candidateName.startsWith("total_") || candidateName.startsWith("overall_")) {
            score += 3;
        }

        if (looksLikeComponentMetric(candidateDescription, candidateName)) {
            score -= 6;
        }
        if (looksLikePartialMetric(candidateDescription)) {
            score -= 5;
        }
        if (familyMismatchPenalty(family, candidateDescription, candidateName) > 0) {
            score -= familyMismatchPenalty(family, candidateDescription, candidateName);
        }

        if (sameSemanticComponent(numerator, candidate)) {
            score -= 8;
        }

        if (descriptionContainsBroaderScope(candidateDescription, description)) {
            score += 4;
        }

        return score;
    }

    private static List<String> totalConcepts(MetricFamily family) {
        return switch (family) {
            case REVENUE -> List.of(
                    "total revenue", "total sales", "total amount", "total charge",
                    "total fare", "total income", "trip charge", "overall revenue");
            case COST -> List.of(
                    "total cost", "operating cost", "total expense", "overall cost",
                    "total spend");
            case PROFIT -> List.of(
                    "total profit", "net profit", "overall profit", "total margin");
            case ENVIRONMENTAL -> List.of(
                    "total emissions", "total carbon", "total energy", "overall emissions",
                    "carbon footprint");
            case GENERIC -> List.of(
                    "total", "overall", "aggregate", "entire");
        };
    }

    private static boolean looksLikeComponentMetric(String description, String columnName) {
        return containsAny(description, "surcharge", "tip paid", "tip amount", "base fare", "pickup fee")
                || containsAny(columnName, "tip_", "_tip", "surcharge", "_fee")
                && !columnName.startsWith("total_");
    }

    private static boolean looksLikePartialMetric(String description) {
        return containsAny(description, "before tips", "before surcharges", "excluding", "subset", "component");
    }

    private static int familyMismatchPenalty(
            MetricFamily family,
            String candidateDescription,
            String candidateName
    ) {
        return switch (family) {
            case REVENUE -> containsAny(candidateDescription + " " + candidateName,
                    "distance", "mile", "hours", "duration", "count", "vendor")
                    ? 8 : 0;
            case COST -> containsAny(candidateDescription + " " + candidateName,
                    "distance", "mile", "revenue", "sales", "profit")
                    ? 6 : 0;
            case PROFIT -> containsAny(candidateDescription + " " + candidateName,
                    "distance", "mile", "hours")
                    ? 6 : 0;
            case ENVIRONMENTAL -> containsAny(candidateDescription + " " + candidateName,
                    "distance", "fare", "tip", "revenue")
                    ? 6 : 0;
            case GENERIC -> 0;
        };
    }

    private static boolean sameSemanticComponent(
            ApprovedCatalogueSnapshot.CatalogueColumn numerator,
            ApprovedCatalogueSnapshot.CatalogueColumn candidate
    ) {
        String num = semanticText(numerator);
        String cand = semanticText(candidate);
        if (num.equals(cand)) {
            return true;
        }
        return containsAny(num, "tip") && containsAny(cand, "tip")
                || containsAny(num, "airport") && containsAny(cand, "airport")
                && containsAny(cand, "fee") && !containsAny(cand, "total");
    }

    private static boolean descriptionContainsBroaderScope(String candidateDescription, String numeratorDescription) {
        if (candidateDescription.isBlank() || numeratorDescription.isBlank()) {
            return false;
        }
        return candidateDescription.contains("including")
                && (numeratorDescription.contains("surcharge")
                || numeratorDescription.contains("tip")
                || numeratorDescription.contains("fee"));
    }

    private static int phraseScore(String text, int weight, String... phrases) {
        int score = 0;
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                score += weight;
            }
        }
        return score;
    }

    private static int phraseScore(String text, int weight, List<String> phrases) {
        int score = 0;
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                score += weight;
            }
        }
        return score;
    }

    private static Optional<ApprovedCatalogueSnapshot.CatalogueColumn> findMetric(
            ApprovedCatalogueSnapshot catalogue,
            String column
    ) {
        return catalogue.columns().stream()
                .filter(c -> c.columnName().equalsIgnoreCase(column))
                .findFirst();
    }

    private static boolean isMetric(ApprovedCatalogueSnapshot.CatalogueColumn column) {
        return column != null && "metric".equalsIgnoreCase(column.role());
    }

    private static String semanticText(ApprovedCatalogueSnapshot.CatalogueColumn column) {
        return normalize(column.description()) + " " + normalize(column.columnName());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String text, String... tokens) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
