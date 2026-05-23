package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Analyses how metric columns change over time.
 *
 * Runs on any table that has both numeric (metric) columns and a date column.
 * Since most real-world tables are MIXED, this agent runs on most tables.
 *
 * For each metric column it produces:
 *   - A time-series query (period → aggregated value) using LLM-enriched metadata
 *     when available (aggregationMethod, comparisonPeriod, maxValue)
 *   - A breakdown of that metric by each dimension column (e.g. revenue by region)
 *
 * Output labels: "Trend: {metric} over time", "{metric} breakdown by {dim}"
 */
@Service
public class TrendAgent {

    private static final int TREND_ROW_LIMIT     = 60;
    private static final int BREAKDOWN_ROW_LIMIT = 10;
    private static final int MAX_METRICS         =  4;
    private static final int MAX_DIMS            =  3;

    private final BigQueryConnectorService  bigQueryConnectorService;
    private final SnowflakeConnectorService snowflakeConnectorService;

    public TrendAgent(BigQueryConnectorService  bigQueryConnectorService,
                      SnowflakeConnectorService snowflakeConnectorService) {
        this.bigQueryConnectorService  = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
    }

    /**
     * Collect trend and breakdown data for a table.
     * Returns an empty list if the table has no metric columns or no date column.
     */
    public List<CollectedData> collectData(TableContext ctx) {
        List<CollectedData> results = new ArrayList<>();

        List<String> metrics = limit(ctx.hints().numericCols(), MAX_METRICS);
        List<String> dims    = limit(ctx.hints().stringCols(),  MAX_DIMS);
        String dateCol       = ctx.hints().dateCol();

        if (metrics.isEmpty()) return results;  // nothing to trend

        for (String metric : metrics) {
            EnrichedColInfo metricInfo = ctx.enriched().get(metric.toLowerCase());
            EnrichedColInfo dateInfo   = dateCol != null
                    ? ctx.enriched().get(dateCol.toLowerCase()) : null;

            // Time-series trend
            String trendSql = buildTrendSql(ctx.tableRef(), metric, dateCol,
                    ctx.provider(), metricInfo, dateInfo);
            safeExecute(trendSql, "Trend: " + metric + " over time", ctx, results);

            // Breakdown by each dimension column
            for (String dim : dims) {
                String breakdownSql = buildBreakdownSql(ctx.tableRef(), metric, dim,
                        ctx.provider(), metricInfo);
                safeExecute(breakdownSql, metric + " breakdown by " + dim, ctx, results);
            }
        }

        return results;
    }

    // ── SQL builders ─────────────────────────────────────────────────────────

    private String buildTrendSql(String tableRef, String metric, String dateCol,
                                  String provider, EnrichedColInfo metricInfo,
                                  EnrichedColInfo dateInfo) {
        boolean isBQ      = "bigquery".equalsIgnoreCase(provider);
        String  metricRef = isBQ ? "`" + metric + "`" : metric;

        if (dateCol == null) {
            return String.format("SELECT %s FROM %s ORDER BY %s DESC LIMIT %d",
                    metricRef, tableRef, metricRef, TREND_ROW_LIMIT);
        }

        String dateRef    = isBQ ? "`" + dateCol + "`" : dateCol;
        String aggMethod  = metricInfo != null ? metricInfo.aggregationMethod() : "NONE";
        String compPeriod = metricInfo != null ? metricInfo.comparisonPeriod()  : "NONE";
        String dataMax    = dateInfo   != null ? dateInfo.maxValue()            : "";

        // Use enriched aggregation + period when set by the LLM enricher
        if (List.of("SUM", "COUNT", "AVG").contains(aggMethod)
                && List.of("WoW", "MoM", "YoY").contains(compPeriod)) {

            String groupExpr = dateTrunc(dateRef, compPeriod, provider);
            String aggExpr   = aggExpr(metricRef, aggMethod);
            String lookback  = lookbackFilter(dateRef, dataMax, compPeriod, provider);

            return String.format(
                    "SELECT %s AS period, %s AS metric_value FROM %s%s " +
                    "GROUP BY period ORDER BY period DESC LIMIT %d",
                    groupExpr, aggExpr, tableRef, lookback, TREND_ROW_LIMIT);
        }

        // Fallback: raw time series
        String lookback = lookbackFilter(dateRef, dataMax, "MoM", provider);
        return String.format("SELECT %s, %s FROM %s%s ORDER BY %s DESC LIMIT %d",
                dateRef, metricRef, tableRef, lookback, dateRef, TREND_ROW_LIMIT);
    }

    private String buildBreakdownSql(String tableRef, String metric, String dim,
                                      String provider, EnrichedColInfo metricInfo) {
        boolean isBQ     = "bigquery".equalsIgnoreCase(provider);
        String dimRef    = isBQ ? "`" + dim    + "`" : dim;
        String metricRef = isBQ ? "`" + metric + "`" : metric;

        String agg    = (metricInfo != null && !"NONE".equals(metricInfo.aggregationMethod()))
                ? metricInfo.aggregationMethod() : "SUM";
        String aggExpr = aggExpr(metricRef, agg);

        return String.format(
                "SELECT %s, %s AS total FROM %s GROUP BY %s ORDER BY total DESC LIMIT %d",
                dimRef, aggExpr, tableRef, dimRef, BREAKDOWN_ROW_LIMIT);
    }

    // ── Shared SQL helpers ────────────────────────────────────────────────────

    private String dateTrunc(String dateRef, String period, String provider) {
        boolean isBQ = "bigquery".equalsIgnoreCase(provider);
        boolean isSF = "snowflake".equalsIgnoreCase(provider);
        String unit = switch (period) {
            case "WoW" -> "WEEK";
            case "YoY" -> "YEAR";
            default    -> "MONTH";
        };
        if (isBQ) return "DATE_TRUNC(" + dateRef + ", " + unit + ")";
        if (isSF) return "DATE_TRUNC('" + unit + "', " + dateRef + ")";
        return "DATE_TRUNC('" + unit.toLowerCase() + "', " + dateRef + ")";
    }

    private String aggExpr(String col, String method) {
        return switch (method) {
            case "SUM"   -> "SUM("   + col + ")";
            case "COUNT" -> "COUNT(" + col + ")";
            default      -> "AVG("   + col + ")";
        };
    }

    private String lookbackFilter(String dateRef, String dataMax,
                                   String period, String provider) {
        if (dataMax == null || dataMax.isBlank() || dataMax.length() < 10) return "";
        try {
            LocalDate maxDate = LocalDate.parse(dataMax.substring(0, 10));
            int months = switch (period) {
                case "WoW" ->  6;
                case "YoY" -> 60;
                default    -> 24;
            };
            String start = maxDate.minusMonths(months).toString();
            boolean isBQ = "bigquery".equalsIgnoreCase(provider);
            boolean isSF = "snowflake".equalsIgnoreCase(provider);
            if (isBQ) return " WHERE " + dateRef + " >= DATE '" + start + "'";
            if (isSF) return " WHERE " + dateRef + " >= '" + start + "'::DATE";
            return " WHERE " + dateRef + " >= '" + start + "'";
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    // ── Query execution ───────────────────────────────────────────────────────

    private void safeExecute(String sql, String label, TableContext ctx,
                              List<CollectedData> out) {
        try {
            List<Map<String, Object>> rows = execute(sql, ctx);
            if (rows != null && !rows.isEmpty()) {
                out.add(new CollectedData(label, sql, rows));
            }
        } catch (Exception e) {
            System.out.printf("[TrendAgent] Query failed [%s]: %s%n", label, e.getMessage());
        }
    }

    private List<Map<String, Object>> execute(String sql, TableContext ctx) {
        if (ctx.useBQ() && ctx.bqCfg().isPresent()) {
            var c = ctx.bqCfg().get();
            return bigQueryConnectorService.executeSelect(
                    c.projectId(), c.serviceAccountJson(), c.location(), c.dataset(), sql);
        } else if (ctx.useSF() && ctx.sfCfg().isPresent()) {
            var c = ctx.sfCfg().get();
            return snowflakeConnectorService.executeSelect(
                    c.account(), c.warehouse(), c.database(),
                    c.schema(), c.username(), c.password(), sql);
        } else {
            return ctx.jdbcTemplate().queryForList(sql);
        }
    }

    private <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }
}
