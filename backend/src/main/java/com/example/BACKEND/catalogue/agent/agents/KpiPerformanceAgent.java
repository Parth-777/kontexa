package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
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
 * Tracks how KPIs are performing relative to a prior period.
 *
 * For each numeric column it runs an aggregated "current period vs prior period"
 * query (split by record count halves when no explicit date window is available).
 * The result is used both for KPI cards in the dashboard and as LLM context.
 *
 * Works on any table with metric columns — the date column is optional.
 *
 * Output labels: "KPI: {metric} current period", "KPI: {metric} prior period"
 * Also produces in-memory KpiCard objects that the orchestrator can attach directly.
 */
@Service
public class KpiPerformanceAgent {

    private static final int KPI_SAMPLE  = 30;
    private static final int MAX_METRICS = 4;

    private final BigQueryConnectorService  bigQueryConnectorService;
    private final SnowflakeConnectorService snowflakeConnectorService;

    public KpiPerformanceAgent(BigQueryConnectorService  bigQueryConnectorService,
                               SnowflakeConnectorService snowflakeConnectorService) {
        this.bigQueryConnectorService  = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
    }

    /**
     * Collects KPI data and builds KPI cards in a single pass.
     * The returned CollectedData is also forwarded to the LLM for context.
     */
    public KpiResult collectData(TableContext ctx) {
        List<CollectedData>               collected = new ArrayList<>();
        List<AgentDashboardResult.KpiCard> cards    = new ArrayList<>();

        List<String> metrics = limit(ctx.hints().numericCols(), MAX_METRICS);
        String       dateCol = ctx.hints().dateCol();

        for (String metric : metrics) {
            EnrichedColInfo metricInfo = ctx.enriched().get(metric.toLowerCase());
            EnrichedColInfo dateInfo   = dateCol != null
                    ? ctx.enriched().get(dateCol.toLowerCase()) : null;

            // Fetch ordered time-series data to split into current vs prior
            String sql = buildKpiSql(ctx.tableRef(), metric, dateCol, ctx.provider(),
                    metricInfo, dateInfo);
            List<Map<String, Object>> rows = safeExecute(sql, ctx);
            if (rows == null || rows.isEmpty()) continue;

            collected.add(new CollectedData("KPI: " + metric, sql, rows));

            // Build a KPI card from the data
            AgentDashboardResult.KpiCard card = buildCard(metric, rows, metricInfo);
            if (card != null) cards.add(card);
        }

        return new KpiResult(collected, cards);
    }

    public record KpiResult(
            List<CollectedData> collected,
            List<AgentDashboardResult.KpiCard> cards
    ) {}

    // ── KPI card calculation ──────────────────────────────────────────────────

    private AgentDashboardResult.KpiCard buildCard(String metric,
                                                    List<Map<String, Object>> rows,
                                                    EnrichedColInfo metricInfo) {
        if (rows.size() < 2) return null;

        // Find the primary numeric value column
        String valKey = rows.get(0).keySet().stream()
                .filter(k -> isNumeric(rows.get(0).get(k)))
                .findFirst().orElse(null);
        if (valKey == null) return null;

        int half   = Math.max(1, rows.size() / 2);
        double curr = average(rows.subList(0, half), valKey);
        double prev = average(rows.subList(half, rows.size()), valKey);

        double change    = prev == 0 ? 0 : ((curr - prev) / Math.abs(prev)) * 100.0;
        String direction = change >  0.5 ? "UP" : change < -0.5 ? "DOWN" : "FLAT";

        String label = (metricInfo != null && !metricInfo.businessMeaning().isBlank())
                ? metricInfo.businessMeaning() : metric;

        return new AgentDashboardResult.KpiCard(
                label, formatValue(curr), curr, prev,
                Math.round(change * 10.0) / 10.0, direction);
    }

    private double average(List<Map<String, Object>> rows, String key) {
        return rows.stream()
                .mapToDouble(r -> toDouble(r.get(key)))
                .average().orElse(0);
    }

    private boolean isNumeric(Object v) {
        if (v instanceof Number) return true;
        if (v == null) return false;
        try { Double.parseDouble(v.toString()); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private String formatValue(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (v >= 1_000)     return String.format("%.1fK", v / 1_000);
        return String.format("%.1f", v);
    }

    // ── SQL builder ───────────────────────────────────────────────────────────

    private String buildKpiSql(String tableRef, String metric, String dateCol,
                                String provider, EnrichedColInfo metricInfo,
                                EnrichedColInfo dateInfo) {
        boolean isBQ      = "bigquery".equalsIgnoreCase(provider);
        String  metricRef = isBQ ? "`" + metric + "`" : metric;

        if (dateCol == null) {
            return String.format("SELECT %s FROM %s ORDER BY %s DESC LIMIT %d",
                    metricRef, tableRef, metricRef, KPI_SAMPLE);
        }

        String dateRef    = isBQ ? "`" + dateCol + "`" : dateCol;
        String aggMethod  = metricInfo != null ? metricInfo.aggregationMethod() : "NONE";
        String compPeriod = metricInfo != null ? metricInfo.comparisonPeriod()  : "NONE";
        String dataMax    = dateInfo   != null ? dateInfo.maxValue()            : "";

        if (List.of("SUM", "COUNT", "AVG").contains(aggMethod)
                && List.of("WoW", "MoM", "YoY").contains(compPeriod)) {

            String groupExpr = dateTrunc(dateRef, compPeriod, provider);
            String aggExpr   = aggExpr(metricRef, aggMethod);
            String lookback  = lookbackFilter(dateRef, dataMax, compPeriod, provider);

            return String.format(
                    "SELECT %s AS period, %s AS metric_value FROM %s%s " +
                    "GROUP BY period ORDER BY period DESC LIMIT %d",
                    groupExpr, aggExpr, tableRef, lookback, KPI_SAMPLE);
        }

        // Fallback: recent raw values ordered by date
        return String.format("SELECT %s, %s FROM %s ORDER BY %s DESC LIMIT %d",
                dateRef, metricRef, tableRef, dateRef, KPI_SAMPLE);
    }

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

    private List<Map<String, Object>> safeExecute(String sql, TableContext ctx) {
        try {
            return execute(sql, ctx);
        } catch (Exception e) {
            System.out.printf("[KpiAgent] Query failed: %s%n", e.getMessage());
            return List.of();
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
