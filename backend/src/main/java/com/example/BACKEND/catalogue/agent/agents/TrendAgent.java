package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.RollupDataSource;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TrendAgent {

    private static final int TREND_ROW_LIMIT     = 60;
    private static final int BREAKDOWN_ROW_LIMIT = 10;

    private final ScaleAwareQueryExecutor queryExecutor;
    private final RollupDataSource rollupDataSource;

    public TrendAgent(ScaleAwareQueryExecutor queryExecutor, RollupDataSource rollupDataSource) {
        this.queryExecutor = queryExecutor;
        this.rollupDataSource = rollupDataSource;
    }

    public List<CollectedData> collectData(TableContext ctx) {
        if (rollupDataSource.useRollups(ctx)) {
            return rollupDataSource.collectTrends(ctx);
        }

        List<CollectedData> results = new ArrayList<>();

        List<String> metrics = ctx.hints().numericCols();
        List<String> dims    = ctx.hints().stringCols();
        String dateCol       = ctx.hints().dateCol();

        if (metrics.isEmpty()) return results;

        for (String metric : metrics) {
            EnrichedColInfo metricInfo = ctx.enriched().get(metric.toLowerCase());
            EnrichedColInfo dateInfo   = dateCol != null
                    ? ctx.enriched().get(dateCol.toLowerCase()) : null;

            String trendSql = buildTrendSql(ctx, metric, dateCol, metricInfo, dateInfo);
            safeExecute(trendSql, "Trend: " + metric + " over time", ctx, results);

            for (String dim : dims) {
                String breakdownSql = buildBreakdownSql(ctx, metric, dim, metricInfo);
                safeExecute(breakdownSql, metric + " breakdown by " + dim, ctx, results);
            }
        }

        return results;
    }

    private String buildTrendSql(TableContext ctx, String metric, String dateCol,
                                  EnrichedColInfo metricInfo, EnrichedColInfo dateInfo) {
        String provider  = ctx.provider();
        String tableRef  = ctx.tableRef();
        String metricRef = AgentSqlHelper.qualify(metric, provider);

        if (dateCol == null) {
            return String.format("SELECT %s FROM %s ORDER BY %s DESC LIMIT %d",
                    metricRef, tableRef, metricRef, TREND_ROW_LIMIT);
        }

        String dateRef    = AgentSqlHelper.qualify(dateCol, provider);
        String aggMethod  = metricInfo != null ? metricInfo.aggregationMethod() : "NONE";
        String compPeriod = metricInfo != null ? metricInfo.comparisonPeriod()  : "NONE";
        String dataMax    = dateInfo   != null ? dateInfo.maxValue()            : "";
        String window     = resolveWindow(ctx, dateRef, dataMax, compPeriod, provider);

        if (List.of("SUM", "COUNT", "AVG").contains(aggMethod)
                && List.of("WoW", "MoM", "YoY").contains(compPeriod)) {

            String groupExpr = dateTrunc(dateRef, compPeriod, provider);
            String aggExpr   = aggExpr(metricRef, aggMethod);

            return String.format(
                    "SELECT %s AS period, %s AS metric_value FROM %s " +
                    "GROUP BY period ORDER BY period DESC LIMIT %d",
                    groupExpr, aggExpr, AgentSqlHelper.qualifiedTableRef(ctx) + window, TREND_ROW_LIMIT);
        }

        return String.format("SELECT %s, %s FROM %s ORDER BY %s DESC LIMIT %d",
                dateRef, metricRef, AgentSqlHelper.qualifiedTableRef(ctx) + window, dateRef, TREND_ROW_LIMIT);
    }

    private String buildBreakdownSql(TableContext ctx, String metric, String dim,
                                      EnrichedColInfo metricInfo) {
        String provider  = ctx.provider();
        String tableRef  = ctx.tableRef();
        String dimRef    = AgentSqlHelper.qualify(dim, provider);
        String metricRef = AgentSqlHelper.qualify(metric, provider);

        String agg = (metricInfo != null && !"NONE".equals(metricInfo.aggregationMethod()))
                ? metricInfo.aggregationMethod() : "SUM";
        String aggExpression = aggExpr(metricRef, agg);

        String window = AgentSqlHelper.windowClause(ctx);
        if (window.isEmpty() && ctx.hints().dateCol() != null) {
            String dateRef = AgentSqlHelper.qualify(ctx.hints().dateCol(), provider);
            window = resolveWindow(ctx, dateRef, "", "MoM", provider);
        }

        return String.format(
                "SELECT %s, %s AS total FROM %s GROUP BY %s ORDER BY total DESC LIMIT %d",
                dimRef, aggExpression, AgentSqlHelper.qualifiedTableRef(ctx) + window, dimRef, BREAKDOWN_ROW_LIMIT);
    }

    private String resolveWindow(TableContext ctx, String dateRef, String dataMax,
                                String period, String provider) {
        String scaleWindow = AgentSqlHelper.windowClause(ctx);
        if (!scaleWindow.isEmpty()) return scaleWindow;
        return lookbackFilter(dateRef, dataMax, period, provider);
    }

    private String dateTrunc(String dateRef, String period, String provider) {
        String unit = switch (period) {
            case "WoW" -> "WEEK";
            case "YoY" -> "YEAR";
            default    -> "MONTH";
        };
        return AgentSqlHelper.dateTrunc(dateRef, unit, provider);
    }

    private String aggExpr(String col, String method) {
        return switch (method) {
            case "SUM"   -> "SUM("   + col + ")";
            case "COUNT" -> "COUNT(" + col + ")";
            default      -> "AVG("   + col + ")";
        };
    }

    private String lookbackFilter(String dateRef, String dataMax, String period, String provider) {
        String dateExpr = AgentSqlHelper.asDateExpr(dateRef, provider);
        if (dataMax == null || dataMax.isBlank() || dataMax.length() < 10) {
            LocalDate start = LocalDate.now().minusMonths(24);
            boolean isBQ = "bigquery".equalsIgnoreCase(provider);
            if (isBQ) return " WHERE " + dateExpr + " >= DATE '" + start + "'";
            return " WHERE " + dateExpr + " >= '" + start + "'";
        }
        try {
            LocalDate maxDate = LocalDate.parse(dataMax.substring(0, 10));
            int months = switch (period) {
                case "WoW" -> 6;
                case "YoY" -> 60;
                default   -> 24;
            };
            String start = maxDate.minusMonths(months).toString();
            boolean isBQ = "bigquery".equalsIgnoreCase(provider);
            boolean isSF = "snowflake".equalsIgnoreCase(provider);
            if (isBQ) return " WHERE " + dateExpr + " >= DATE '" + start + "'";
            if (isSF) return " WHERE " + dateExpr + " >= '" + start + "'::DATE";
            return " WHERE " + dateExpr + " >= '" + start + "'";
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    private void safeExecute(String sql, String label, TableContext ctx, List<CollectedData> out) {
        List<Map<String, Object>> rows = queryExecutor.execute(sql, label, ctx);
        if (rows != null && !rows.isEmpty()) {
            out.add(new CollectedData(label, sql, rows));
        }
    }
}
