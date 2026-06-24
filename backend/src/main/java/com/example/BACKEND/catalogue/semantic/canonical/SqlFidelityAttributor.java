package com.example.BACKEND.catalogue.semantic.canonical;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps SQL fidelity mutations to the most likely production code owner based on
 * query metadata and mutation field — no per-question hardcoding.
 */
public final class SqlFidelityAttributor {

    private static final Pattern TEMPLATE_KEY = Pattern.compile(
            "tpl__([a-z_]+)__", Pattern.CASE_INSENSITIVE);

    private SqlFidelityAttributor() {}

    public static String templateFromQueryKey(String queryKey) {
        if (queryKey == null || queryKey.isBlank()) {
            return "AnalysisPlanSqlGenerator";
        }
        Matcher matcher = TEMPLATE_KEY.matcher(queryKey.toLowerCase(Locale.ROOT));
        if (matcher.find()) {
            return toTemplateClass(matcher.group(1));
        }
        if (queryKey.contains("composition")) {
            return "AnalysisPlanSqlGenerator";
        }
        if (queryKey.contains("scalar")) {
            return "SemanticPlanScalarSqlBuilder";
        }
        return "AnalysisPlanSqlGenerator";
    }

    public static String primarySuspect(
            SqlFidelityReport.Mutation mutation,
            String plannerIntent,
            String queryKey
    ) {
        String template = templateFromQueryKey(queryKey);
        return switch (mutation.field()) {
            case "aggregation" -> "IntentAggregationStrategy";
            case "relationshipOperands" -> "RELATIONSHIP".equalsIgnoreCase(plannerIntent)
                    ? "RelationshipSqlTemplate"
                    : template;
            case "limit" -> "RankingSqlTemplate".equals(template) ? template : "SqlRenderPostProcessor";
            case "timeGrain" -> "TrendSqlTemplate".equals(template) ? template : "DimensionBucketingSql";
            case "partition" -> "SemanticPlanToAnalysisPlanAdapter";
            case "ordering" -> "SqlRenderPostProcessor";
            case "measure" -> template;
            default -> template;
        };
    }

    public static Map<String, Map<String, Integer>> rankFailures(
            List<CaseAttribution> cases
    ) {
        Map<String, Map<String, Integer>> ranked = new LinkedHashMap<>();
        for (CaseAttribution c : cases) {
            for (SqlFidelityReport.Mutation mutation : c.mutations()) {
                String field = mutation.field();
                String suspect = primarySuspect(mutation, c.plannerIntent(), c.queryKey());
                ranked.computeIfAbsent(field, k -> new LinkedHashMap<>())
                        .merge(suspect, 1, Integer::sum);
            }
        }
        return ranked;
    }

    public record CaseAttribution(
            String plannerIntent,
            String queryKey,
            List<SqlFidelityReport.Mutation> mutations
    ) {}

    private static String toTemplateClass(String token) {
        String normalized = token.replace('_', ' ');
        String[] parts = normalized.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        sb.append("SqlTemplate");
        return sb.toString();
    }
}
