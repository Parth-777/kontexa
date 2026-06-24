package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Post-SQL filter injection for GPT plans. Does not modify production SQL templates.
 */
public final class SemanticPlanFilterAugmenter {

    private static final Pattern GROUP_BY = Pattern.compile("(?i)\\s+GROUP\\s+BY\\s+");

    private SemanticPlanFilterAugmenter() {}

    public static QuerySpec applyFilters(QuerySpec base, List<StructuredSemanticPlan.SemanticFilter> filters) {
        if (base == null || filters == null || filters.isEmpty()) return base;
        String clause = filters.stream()
                .map(SemanticPlanFilterAugmenter::toSqlPredicate)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" AND "));
        if (clause.isBlank()) return base;

        String sql = base.sql();
        Matcher gb = GROUP_BY.matcher(sql);
        if (gb.find()) {
            sql = sql.substring(0, gb.start()) + " WHERE " + clause + sql.substring(gb.start());
        } else {
            sql = sql.trim() + " WHERE " + clause;
        }
        return new QuerySpec(base.key(), sql, base.params());
    }

    public static List<QuerySpec> applyFilters(
            List<QuerySpec> specs,
            List<StructuredSemanticPlan.SemanticFilter> filters
    ) {
        if (specs == null) return List.of();
        List<QuerySpec> out = new ArrayList<>();
        for (QuerySpec s : specs) {
            out.add(applyFilters(s, filters));
        }
        return out;
    }

    public static String toSqlPredicate(StructuredSemanticPlan.SemanticFilter f) {
        String op = f.operator() == null ? "=" : f.operator().trim().toUpperCase(Locale.ROOT);
        String val = f.value() == null ? "" : f.value().replace("'", "''");
        return switch (op) {
            case "IN" -> f.column() + " IN (" + val + ")";
            case "LIKE" -> f.column() + " LIKE '" + val + "'";
            case ">", ">=", "<", "<=", "=", "!=", "<>" ->
                    f.column() + " " + op + " '" + val + "'";
            default -> f.column() + " = '" + val + "'";
        };
    }
}
