package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationEngine;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Generates fallback SQL variants when grouped aggregation returns zero rows.
 *
 * A. remove HAVING
 * B. remove WHERE filters
 * C. remove bucket — raw dimension GROUP BY
 * D. switch aggregation SUM → AVG
 * E. TOP 10 raw grouping
 */
@Component
public class SqlFallbackExecutionChain {

    private static final Pattern HAVING = Pattern.compile("(?i)\\s+HAVING\\s+.+$");
    private static final Pattern WHERE = Pattern.compile("(?i)\\s+WHERE\\s+.+?(\\s+GROUP\\s+BY|$)");

    private final SemanticTransformationEngine transformationEngine;
    private final IntentAggregationStrategy aggregationStrategy;

    public SqlFallbackExecutionChain(
            SemanticTransformationEngine transformationEngine,
            IntentAggregationStrategy aggregationStrategy
    ) {
        this.transformationEngine = transformationEngine;
        this.aggregationStrategy = aggregationStrategy;
    }

    public List<String> fallbacks(String primarySql, TemplateContext ctx) {
        List<String> chain = new ArrayList<>();
        if (primarySql == null || primarySql.isBlank()) return chain;

        String a = HAVING.matcher(primarySql).replaceAll("");
        if (!a.equals(primarySql)) chain.add(a);

        String b = stripWhere(primarySql);
        if (!b.equals(primarySql) && !chain.contains(b)) chain.add(b);

        chain.add(derivedDimensionGroupBy(ctx));
        chain.add(rawDimensionGroupBy(ctx));
        if (aggregationStrategy.allowsAvgFallback(ctx.intent())) {
            chain.add(switchToAvg(ctx));
        }
        chain.add(top10Raw(ctx));

        return chain.stream().distinct().toList();
    }

    private String stripWhere(String sql) {
        if (!sql.toLowerCase(Locale.ROOT).contains(" where ")) return sql;
        return WHERE.matcher(sql).replaceAll(" $1");
    }

    private String derivedDimensionGroupBy(TemplateContext ctx) {
        if (ctx.bucketExpression() != null && ctx.bucketAlias() != null
                && !ctx.bucketExpression().equals(ctx.dimensionColumn())) {
            return """
                    SELECT
                      %s AS %s,
                      SUM(%s) AS revenue
                    FROM %s
                    GROUP BY %s
                    ORDER BY revenue DESC""".formatted(
                    ctx.bucketExpression(), ctx.bucketAlias(),
                    ctx.revenueMetric(), ctx.tableRef(), ctx.bucketAlias());
        }
        SemanticTransformationResult derived = transformationEngine.transformWithFallbacks(
                ctx.question(), ctx.tableRef(), ctx.revenueMetric(),
                ctx.dimensionColumn(), ctx.bucketAlias(), ctx.intent(),
                ctx.candidateId() + "_derived", null);
        if (derived.success() && derived.templateContext() != null) {
            TemplateContext t = derived.templateContext();
            return """
                    SELECT
                      %s AS %s,
                      SUM(%s) AS revenue
                    FROM %s
                    GROUP BY %s
                    ORDER BY revenue DESC""".formatted(
                    t.bucketExpression(), t.bucketAlias(),
                    t.revenueMetric(), t.tableRef(), t.bucketAlias());
        }
        return primarySqlIfGrouped(ctx);
    }

    private String primarySqlIfGrouped(TemplateContext ctx) {
        return rawDimensionGroupBy(ctx);
    }

    private String rawDimensionGroupBy(TemplateContext ctx) {
        String dim = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : "segment";
        return """
                SELECT
                  %s AS %s,
                  SUM(%s) AS revenue
                FROM %s
                GROUP BY %s
                ORDER BY revenue DESC
                LIMIT 20""".formatted(dim, dim, ctx.revenueMetric(), ctx.tableRef(), dim);
    }

    private String switchToAvg(TemplateContext ctx) {
        return """
                SELECT
                  %s AS %s,
                  AVG(%s) AS revenue
                FROM %s
                GROUP BY %s
                ORDER BY revenue DESC""".formatted(
                ctx.bucketExpression(), ctx.bucketAlias(),
                ctx.revenueMetric(), ctx.tableRef(), ctx.bucketAlias());
    }

    private String top10Raw(TemplateContext ctx) {
        String dim = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : ctx.revenueMetric();
        return """
                SELECT
                  CAST(%s AS STRING) AS entity,
                  SUM(%s) AS revenue
                FROM %s
                GROUP BY entity
                ORDER BY revenue DESC
                LIMIT 10""".formatted(dim, ctx.revenueMetric(), ctx.tableRef());
    }
}
