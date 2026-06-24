package com.example.BACKEND.catalogue.decision.execution.repair;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates progressively safer SQL alternatives when grouped queries return zero rows.
 */
@Component
public class ZeroRowRecoveryEngine {

    private static final Pattern HAVING = Pattern.compile("(?i)\\s+HAVING\\s+.+$");
    private static final Pattern WHERE = Pattern.compile("(?i)\\s+WHERE\\s+.+?(\\s+GROUP\\s+BY|$)");

    public record RecoveryCandidate(String strategy, String sql, String rationale) {}

    public List<RecoveryCandidate> buildRecoveryChain(
            String primarySql, TemplateContext ctx, ExecutionDiagnostics failedAttempt
    ) {
        List<RecoveryCandidate> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        add(chain, seen, "primary", primarySql, "Initial generated query");

        addStripHaving(chain, seen, primarySql);
        addStripWhere(chain, seen, primarySql);
        addBucketGroupBy(chain, seen, ctx);
        addRawDimension(chain, seen, ctx);
        addRoundNumeric(chain, seen, ctx);
        addFloorNumeric(chain, seen, ctx);
        addTopNRaw(chain, seen, ctx);
        addAvgAggregation(chain, seen, ctx);

        if (failedAttempt != null && "ZERO_ROWS".equals(failedAttempt.failureReason())) {
            addNoFilterScalar(chain, seen, ctx);
        }

        return chain;
    }

    private void addStripHaving(List<RecoveryCandidate> chain, Set<String> seen, String sql) {
        String stripped = HAVING.matcher(sql).replaceAll("");
        if (!stripped.equals(sql)) {
            add(chain, seen, "remove_having", stripped, "Removed HAVING filter that may exclude all rows");
        }
    }

    private void addStripWhere(List<RecoveryCandidate> chain, Set<String> seen, String sql) {
        if (!sql.toLowerCase(Locale.ROOT).contains(" where ")) return;
        String stripped = WHERE.matcher(sql).replaceAll(" $1");
        if (!stripped.equals(sql)) {
            add(chain, seen, "remove_where", stripped, "Removed WHERE filter for broader row coverage");
        }
    }

    private void addBucketGroupBy(List<RecoveryCandidate> chain, Set<String> seen, TemplateContext ctx) {
        if (ctx.bucketExpression() == null || ctx.bucketAlias() == null) return;
        if (ctx.bucketExpression().equals(ctx.dimensionColumn())) return;
        String sql = groupedSql(ctx.bucketExpression(), ctx.bucketAlias(),
                ctx.revenueMetric(), ctx.tableRef(), ctx.bucketAlias(), 20);
        add(chain, seen, "bucket_expression", sql,
                "Retry grouping with derived bucket expression");
    }

    private void addRawDimension(List<RecoveryCandidate> chain, Set<String> seen, TemplateContext ctx) {
        String dim = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : "segment";
        String sql = groupedSql(dim, dim, ctx.revenueMetric(), ctx.tableRef(), dim, 20);
        add(chain, seen, "raw_dimension", sql,
                "Retry with raw dimension grouping instead of buckets");
    }

    private void addRoundNumeric(List<RecoveryCandidate> chain, Set<String> seen, TemplateContext ctx) {
        String dim = ctx.dimensionColumn();
        if (dim == null || !isNumericName(dim)) return;
        String expr = "ROUND(" + dim + ", 0)";
        String sql = groupedSql(expr, "rounded_" + dim, ctx.revenueMetric(), ctx.tableRef(),
                "rounded_" + dim, 20);
        add(chain, seen, "round_numeric", sql,
                "Retry with ROUND(" + dim + ") grouping for sparse numeric distribution");
    }

    private void addFloorNumeric(List<RecoveryCandidate> chain, Set<String> seen, TemplateContext ctx) {
        String dim = ctx.dimensionColumn();
        if (dim == null || !isNumericName(dim)) return;
        String expr = "CAST(FLOOR(" + dim + ") AS STRING)";
        String sql = groupedSql(expr, "floor_" + dim, ctx.revenueMetric(), ctx.tableRef(),
                "floor_" + dim, 20);
        add(chain, seen, "floor_numeric", sql,
                "Retry with FLOOR bucket for continuous numeric dimension");
    }

    private void addTopNRaw(List<RecoveryCandidate> chain, Set<String> seen, TemplateContext ctx) {
        String dim = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : ctx.revenueMetric();
        String sql = """
                SELECT
                  CAST(%s AS STRING) AS entity,
                  SUM(%s) AS revenue
                FROM %s
                GROUP BY entity
                ORDER BY revenue DESC
                LIMIT 10""".formatted(dim, ctx.revenueMetric(), ctx.tableRef());
        add(chain, seen, "top_n_raw", sql, "Top-N raw grouping fallback");
    }

    private void addAvgAggregation(List<RecoveryCandidate> chain, Set<String> seen, TemplateContext ctx) {
        String expr = ctx.bucketExpression() != null ? ctx.bucketExpression() : ctx.dimensionColumn();
        String alias = ctx.bucketAlias() != null ? ctx.bucketAlias() : "segment";
        if (expr == null) return;
        String sql = """
                SELECT
                  %s AS %s,
                  AVG(%s) AS revenue
                FROM %s
                GROUP BY %s
                ORDER BY revenue DESC
                LIMIT 20""".formatted(expr, alias, ctx.revenueMetric(), ctx.tableRef(), alias);
        add(chain, seen, "avg_aggregation", sql, "Switch SUM to AVG aggregation");
    }

    private void addNoFilterScalar(List<RecoveryCandidate> chain, Set<String> seen, TemplateContext ctx) {
        String dim = ctx.dimensionColumn() != null ? ctx.dimensionColumn() : ctx.revenueMetric();
        String sql = """
                SELECT
                  MIN(%s) AS min_value,
                  MAX(%s) AS max_value,
                  COUNT(*) AS row_count,
                  SUM(%s) AS total_revenue
                FROM %s""".formatted(dim, dim, ctx.revenueMetric(), ctx.tableRef());
        add(chain, seen, "bounds_probe", sql,
                "Probe MIN/MAX bounds when grouped queries return no rows");
    }

    private String groupedSql(
            String groupExpr, String alias, String metric, String table, String groupBy, int limit
    ) {
        return """
                SELECT
                  %s AS %s,
                  SUM(%s) AS revenue
                FROM %s
                GROUP BY %s
                ORDER BY revenue DESC
                LIMIT %d""".formatted(groupExpr, alias, metric, table, groupBy, limit);
    }

    private boolean isNumericName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("distance") || n.contains("amount") || n.contains("fare")
                || n.contains("tip") || n.contains("mile");
    }

    private void add(
            List<RecoveryCandidate> chain, Set<String> seen,
            String strategy, String sql, String rationale
    ) {
        if (sql == null || sql.isBlank()) return;
        String normalized = sql.replaceAll("\\s+", " ").trim();
        if (seen.add(normalized)) {
            chain.add(new RecoveryCandidate(strategy, sql, rationale));
        }
    }
}
