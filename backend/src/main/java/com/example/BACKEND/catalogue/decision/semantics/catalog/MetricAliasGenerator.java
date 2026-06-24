package com.example.BACKEND.catalogue.decision.semantics.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds query-facing aliases for registry metrics from column names and labels only.
 * No dataset-specific hardcoding — expansions are generic business-language clusters.
 */
public final class MetricAliasGenerator {

    /** Generic token → related natural-language terms (domain-agnostic). */
    private static final Map<String, List<String>> TOKEN_EXPANSIONS = Map.ofEntries(
            Map.entry("profit", List.of("profitability", "profitable")),
            Map.entry("margin", List.of("profitability", "profitable", "margin")),
            Map.entry("revenue", List.of("sales", "income", "earnings", "receipts")),
            Map.entry("cost", List.of("expense", "expenses", "spend", "spending", "billing", "charges")),
            Map.entry("pressure", List.of("barometric", "barometric reading", "atmospheric")),
            Map.entry("signal", List.of("transmission", "transmission quality", "signal integrity")),
            Map.entry("emission", List.of("emissions", "carbon")),
            Map.entry("carbon", List.of("emission", "emissions")),
            Map.entry("downtime", List.of("outage", "outages", "idle")),
            Map.entry("maintenance", List.of("upkeep", "repair", "repairs")),
            Map.entry("amount", List.of("value", "total", "revenue")),
            Map.entry("total", List.of("sum", "aggregate", "revenue")),
            Map.entry("churn", List.of("cancellation", "cancellations", "cancel")),
            Map.entry("cancellation", List.of("churn", "cancel")),
            Map.entry("usage", List.of("power", "draw", "energy", "watt", "watts")),
            Map.entry("energy", List.of("power", "draw", "usage", "watt", "watts")),
            Map.entry("power", List.of("energy", "draw", "usage", "watt", "watts")),
            Map.entry("audience", List.of("viewer", "viewers", "view")),
            Map.entry("viewer", List.of("audience", "viewers")),
            Map.entry("prize", List.of("payout", "money")),
            Map.entry("payout", List.of("prize", "money")),
            Map.entry("money", List.of("prize", "payout")),
            Map.entry("brix", List.of("sugar")),
            Map.entry("sugar", List.of("brix")),
            Map.entry("weight", List.of("kilogram", "kilograms")),
            Map.entry("kilogram", List.of("weight", "kilograms")),
            Map.entry("issue", List.of("defect", "quality", "problem")),
            Map.entry("defect", List.of("issue", "quality")),
            Map.entry("quality", List.of("defect", "issue")),
            Map.entry("wind", List.of("windy", "gust", "velocity")),
            Map.entry("rainfall", List.of("rain", "accumulation")),
            Map.entry("throughput", List.of("output", "volume")),
            Map.entry("purse", List.of("prize", "payout"))
    );

    private MetricAliasGenerator() {}

    public static List<String> aliasesFor(String columnName, String label) {
        Set<String> out = new LinkedHashSet<>();
        if (columnName != null && !columnName.isBlank()) {
            out.add(columnName);
            String spaced = columnName.replace('_', ' ').trim();
            out.add(spaced);
            out.add(singularizePhrase(spaced));
            for (String token : spaced.split("\\s+")) {
                if (token.length() < 2) continue;
                out.add(token);
                out.add(stemToken(token));
                expandToken(token, out);
            }
        }
        if (label != null && !label.isBlank()) {
            out.add(label.toLowerCase(Locale.ROOT));
            out.add(singularizePhrase(label.toLowerCase(Locale.ROOT)));
        }
        addSuffixAliases(columnName, out);
        out.remove("");
        return List.copyOf(out);
    }

    private static void addSuffixAliases(String columnName, Set<String> out) {
        if (columnName == null) return;
        String lower = columnName.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_peak")) {
            String prefix = lower.substring(0, lower.length() - "_peak".length()).replace('_', ' ');
            out.add(prefix + " count");
            out.add(prefix + " counts");
        }
        if (lower.endsWith("_payout")) {
            String prefix = lower.substring(0, lower.length() - "_payout".length()).replace('_', ' ');
            out.add(prefix + " money");
        }
    }

    static String stemToken(String token) {
        if (token == null || token.length() < 4) return token != null ? token : "";
        String t = token.toLowerCase(Locale.ROOT);
        if (t.endsWith("ability") && t.length() > 7) return t.substring(0, t.length() - 7);
        if (t.endsWith("ibility") && t.length() > 7) return t.substring(0, t.length() - 7);
        if (t.endsWith("able") && t.length() > 5) return t.substring(0, t.length() - 4);
        if (t.endsWith("ness") && t.length() > 5) return t.substring(0, t.length() - 4);
        if (t.endsWith("ing") && t.length() > 5) return t.substring(0, t.length() - 3);
        if (t.endsWith("ly") && t.length() > 4) return t.substring(0, t.length() - 2);
        if (t.endsWith("ies")) return t.substring(0, t.length() - 3) + "y";
        if (t.endsWith("s") && !t.endsWith("ss") && t.length() > 3) return t.substring(0, t.length() - 1);
        return t;
    }

    private static void expandToken(String token, Set<String> out) {
        String stem = stemToken(token.toLowerCase(Locale.ROOT));
        List<String> expansions = TOKEN_EXPANSIONS.get(stem);
        if (expansions != null) {
            out.addAll(expansions);
            for (String e : expansions) {
                out.add(stemToken(e));
            }
        }
    }

    private static String singularizePhrase(String phrase) {
        String[] parts = phrase.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(stemToken(parts[i]));
        }
        return sb.toString().trim();
    }
}
