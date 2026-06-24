package com.example.BACKEND.catalogue.semantic.phase2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds nearest approved-catalogue column for a planner field (audit only).
 */
public final class CatalogueFieldMatcher {

    private CatalogueFieldMatcher() {}

    public record Match(String columnName, String role, int editDistance) {}

    public static Match nearest(String plannerField, ApprovedCatalogueSnapshot catalogue) {
        if (plannerField == null || plannerField.isBlank() || catalogue == null) {
            return new Match(null, null, Integer.MAX_VALUE);
        }
        List<Match> candidates = new ArrayList<>();
        for (ApprovedCatalogueSnapshot.CatalogueColumn col : catalogue.columns()) {
            int distance = levenshtein(
                    normalize(plannerField),
                    normalize(col.columnName()));
            candidates.add(new Match(col.columnName(), col.role(), distance));
        }
        return candidates.stream()
                .min(Comparator.comparingInt(Match::editDistance)
                        .thenComparing(Match::columnName))
                .orElse(new Match(null, null, Integer.MAX_VALUE));
    }

    public static boolean hasSemanticOverlap(String plannerField, String catalogueColumn) {
        if (plannerField == null || catalogueColumn == null) {
            return false;
        }
        String a = normalize(plannerField);
        String b = normalize(catalogueColumn);
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        return tokenOverlapRatio(a, b) >= 0.5;
    }

    public static boolean hasCommonStem(String plannerField, String catalogueColumn) {
        if (plannerField == null || catalogueColumn == null) {
            return false;
        }
        String a = stem(normalize(plannerField));
        String b = stem(normalize(catalogueColumn));
        if (a.length() < 4 || b.length() < 4) {
            return false;
        }
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }

    public static String suggestedSynonymMapping(String plannerField, Match nearest) {
        if (plannerField == null || nearest == null || nearest.columnName() == null) {
            return null;
        }
        return plannerField + " -> " + nearest.columnName();
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private static String stem(String value) {
        if (value.endsWith("_tons")) {
            return value.substring(0, value.length() - 5);
        }
        if (value.endsWith("_hours")) {
            return value.substring(0, value.length() - 6);
        }
        if (value.endsWith("_cost")) {
            return value.substring(0, value.length() - 5);
        }
        return value;
    }

    private static double tokenOverlapRatio(String a, String b) {
        String[] aTokens = a.split("_");
        String[] bTokens = b.split("_");
        int overlap = 0;
        for (String at : aTokens) {
            for (String bt : bTokens) {
                if (at.length() >= 3 && (at.equals(bt) || at.startsWith(bt) || bt.startsWith(at))) {
                    overlap++;
                    break;
                }
            }
        }
        int denom = Math.max(aTokens.length, bTokens.length);
        return denom == 0 ? 0.0 : (double) overlap / denom;
    }

    static int levenshtein(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
