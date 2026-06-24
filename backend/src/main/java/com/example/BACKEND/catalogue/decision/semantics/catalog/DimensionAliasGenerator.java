package com.example.BACKEND.catalogue.decision.semantics.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds query-facing aliases for registry dimensions from column names and labels.
 * Domain-agnostic synonym clusters only.
 */
public final class DimensionAliasGenerator {

    private static final Map<String, List<String>> TOKEN_EXPANSIONS = Map.ofEntries(
            Map.entry("unit", List.of("ward", "department", "division")),
            Map.entry("ward", List.of("unit", "department")),
            Map.entry("spacecraft", List.of("satellite", "craft", "orbiter")),
            Map.entry("satellite", List.of("spacecraft", "craft", "orbiter")),
            Map.entry("block", List.of("segment", "area", "zone")),
            Map.entry("station", List.of("facility", "site", "terminal")),
            Map.entry("region", List.of("area", "zone", "territory")),
            Map.entry("floor", List.of("line", "area", "zone")),
            Map.entry("line", List.of("floor", "area"))
    );

    private DimensionAliasGenerator() {}

    public static List<String> aliasesFor(String columnName, String label) {
        Set<String> out = new LinkedHashSet<>();
        if (columnName != null && !columnName.isBlank()) {
            out.add(columnName);
            String spaced = columnName.replace('_', ' ').trim();
            out.add(spaced);
            out.add(MetricAliasGenerator.stemToken(spaced));
            for (String token : spaced.split("\\s+")) {
                if (token.length() < 2) continue;
                out.add(token);
                out.add(MetricAliasGenerator.stemToken(token));
                expandToken(token, out);
            }
        }
        if (label != null && !label.isBlank()) {
            out.add(label.toLowerCase(Locale.ROOT));
        }
        out.remove("");
        return List.copyOf(out);
    }

    private static void expandToken(String token, Set<String> out) {
        String stem = MetricAliasGenerator.stemToken(token.toLowerCase(Locale.ROOT));
        List<String> expansions = TOKEN_EXPANSIONS.get(stem);
        if (expansions != null) {
            out.addAll(expansions);
            for (String e : expansions) {
                out.add(MetricAliasGenerator.stemToken(e));
            }
        }
    }
}
