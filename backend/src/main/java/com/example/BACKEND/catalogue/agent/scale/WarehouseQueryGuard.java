package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.TableContext;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class WarehouseQueryGuard {

    private static final Pattern SELECT_STAR = Pattern.compile(
            "(?i)SELECT\\s+\\*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_CLAUSE = Pattern.compile(
            "(?i)\\bLIMIT\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_BY = Pattern.compile(
            "(?i)\\bGROUP\\s+BY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGGREGATE = Pattern.compile(
            "(?i)\\b(SUM|COUNT|AVG|MIN|MAX|STDDEV|APPROX_QUANTILES)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private final ScaleProperties properties;
    private final TableScalePolicy scalePolicy;

    public WarehouseQueryGuard(ScaleProperties properties, TableScalePolicy scalePolicy) {
        this.properties = properties;
        this.scalePolicy = scalePolicy;
    }

    /**
     * Validates and optionally augments SQL. Returns SQL safe to execute.
     */
    public String prepare(String sql, TableContext ctx) {
        if (!properties.isEnabled()) return sql;

        String trimmed = sql.trim();
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            throw new QueryRejectedException("Only SELECT queries are allowed");
        }

        ScaleTier tier = ctx.tier();

        if (tier == ScaleTier.LARGE && SELECT_STAR.matcher(trimmed).find()) {
            if (!trimmed.toUpperCase().contains("COUNT(*)")) {
                throw new QueryRejectedException("SELECT * not allowed on LARGE tables");
            }
        }

        String withWindow = injectWindow(trimmed, ctx);

        if (!hasAggregate(withWindow) && !hasAcceptableLimit(withWindow, tier)) {
            throw new QueryRejectedException("Non-aggregate query must include LIMIT on tier " + tier);
        }

        return withWindow;
    }

    private boolean hasAggregate(String sql) {
        return GROUP_BY.matcher(sql).find() || AGGREGATE.matcher(sql).find();
    }

    private boolean hasAcceptableLimit(String sql, ScaleTier tier) {
        var m = LIMIT_CLAUSE.matcher(sql);
        if (!m.find()) return tier == ScaleTier.SMALL;
        int limit = Integer.parseInt(m.group(1));
        return limit <= properties.getGuardMaxLimitClause();
    }

    private String injectWindow(String sql, TableContext ctx) {
        if (!scalePolicy.requireDateWindow(ctx.tier())) return sql;
        AnalysisWindow window = ctx.window();
        if (window == null || !window.active() || window.dateColumn() == null) return sql;

        String upper = sql.toUpperCase();
        if (upper.contains(" WHERE ")) {
            return sql;
        }

        boolean isBQ = "bigquery".equalsIgnoreCase(ctx.provider());
        String dateRef = isBQ ? "`" + window.dateColumn() + "`" : window.dateColumn();
        String whereClause = window.whereClause(dateRef, ctx.provider());

        // Insert WHERE before LIMIT — appending after LIMIT produces invalid SQL
        var limitMatcher = LIMIT_CLAUSE.matcher(sql);
        if (limitMatcher.find()) {
            int limitStart = limitMatcher.start();
            return sql.substring(0, limitStart).trim() + whereClause + " " + sql.substring(limitStart).trim();
        }
        return sql + whereClause;
    }
}
