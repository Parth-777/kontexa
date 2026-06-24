package com.example.BACKEND.catalogue.agent.scale;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies scale safety rules to LLM-generated ad-hoc SQL (catalogue chat).
 */
@Component
public class ChatSqlScaleGuard {

    private static final Pattern SELECT_STAR = Pattern.compile("(?i)SELECT\\s+\\*");
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)");
    private static final Pattern GROUP_OR_AGG = Pattern.compile(
            "(?i)\\b(GROUP\\s+BY|SUM\\s*\\(|COUNT\\s*\\(|AVG\\s*\\()");

    private final ScaleProperties properties;
    private final TableScalePolicy tableScalePolicy;

    public ChatSqlScaleGuard(ScaleProperties properties, TableScalePolicy tableScalePolicy) {
        this.properties = properties;
        this.tableScalePolicy = tableScalePolicy;
    }

    /**
     * Validates and caps chat SQL based on largest table in the tenant catalogue.
     */
    public String prepare(String sql, JsonNode catalogueNode) {
        if (!properties.isEnabled() || sql == null || sql.isBlank()) return sql;

        String trimmed = sql.trim();
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            throw new QueryRejectedException("Only SELECT queries are allowed in chat");
        }

        long maxRows = 0;
        for (JsonNode t : catalogueNode.path("tables")) {
            maxRows = Math.max(maxRows, t.path("rowCount").asLong(0));
        }
        ScaleTier tier = tableScalePolicy.tier(maxRows);

        if (tier == ScaleTier.LARGE && SELECT_STAR.matcher(trimmed).find()
                && !trimmed.toUpperCase().contains("COUNT(*)")) {
            throw new QueryRejectedException(
                    "SELECT * is not allowed on large tables. Use aggregates (SUM, COUNT, AVG) with GROUP BY.");
        }

        boolean hasAgg = GROUP_OR_AGG.matcher(trimmed).find();
        Matcher limitMatcher = LIMIT_CLAUSE.matcher(trimmed);
        int maxLimit = properties.getGuardMaxLimitClause();

        if (limitMatcher.find()) {
            int limit = Integer.parseInt(limitMatcher.group(1));
            if (limit > maxLimit) {
                return LIMIT_CLAUSE.matcher(trimmed).replaceFirst("LIMIT " + maxLimit);
            }
            return trimmed;
        }

        if (!hasAgg && tier != ScaleTier.SMALL) {
            return trimmed + " LIMIT " + maxLimit;
        }

        if (!hasAgg) {
            return trimmed + " LIMIT " + Math.min(maxLimit, 200);
        }

        return trimmed;
    }
}
