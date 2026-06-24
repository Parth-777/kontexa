package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;
import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.EnrichedColInfo;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
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

    private static final int KPI_SAMPLE = 30;

    private final ScaleAwareQueryExecutor queryExecutor;

    public KpiPerformanceAgent(ScaleAwareQueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    /**
     * Collects KPI data and builds KPI cards in a single pass.
     * The returned CollectedData is also forwarded to the LLM for context.
     */
    public KpiResult collectData(TableContext ctx) {
        List<CollectedData>               collected = new ArrayList<>();
        List<AgentDashboardResult.KpiCard> cards    = new ArrayList<>();

        List<String> metrics = ctx.hints().numericCols();
        String       dateCol = ctx.hints().dateCol();

        for (String metric : metrics) {
            EnrichedColInfo metricInfo = ctx.enriched().get(metric.toLowerCase());
            EnrichedColInfo dateInfo   = dateCol != null
                    ? ctx.enriched().get(dateCol.toLowerCase()) : null;

            // Fetch ordered time-series data to split into current vs prior
            String sql = buildKpiSql(ctx, metric, dateCol, metricInfo, dateInfo);
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

    private String buildKpiSql(TableContext ctx, String metric, String dateCol,
                                EnrichedColInfo metricInfo, EnrichedColInfo dateInfo) {
        String provider  = ctx.provider();
        String tableRef  = ctx.tableRef();
        String metricRef = AgentSqlHelper.qualify(metric, provider);

        if (dateCol == null) {
            return String.format("SELECT %s FROM %s ORDER BY %s DESC LIMIT %d",
                    metricRef, AgentSqlHelper.qualifiedTableRef(ctx), metricRef, KPI_SAMPLE);
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
                    groupExpr, aggExpr, AgentSqlHelper.qualifiedTableRef(ctx) + window, KPI_SAMPLE);
        }

        return String.format("SELECT %s, %s FROM %s ORDER BY %s DESC LIMIT %d",
                dateRef, metricRef, AgentSqlHelper.qualifiedTableRef(ctx) + window, dateRef, KPI_SAMPLE);
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

    private String lookbackFilter(String dateRef, String dataMax,
                                   String period, String provider) {
        String dateExpr = AgentSqlHelper.asDateExpr(dateRef, provider);
        if (dataMax == null || dataMax.isBlank() || dataMax.length() < 10) {
            LocalDate start = LocalDate.now().minusMonths(24);
            if ("bigquery".equalsIgnoreCase(provider)) {
                return " WHERE " + dateExpr + " >= DATE '" + start + "'";
            }
            return " WHERE " + dateExpr + " >= '" + start + "'";
        }
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
            if (isBQ) return " WHERE " + dateExpr + " >= DATE '" + start + "'";
            if (isSF) return " WHERE " + dateExpr + " >= '" + start + "'::DATE";
            return " WHERE " + dateExpr + " >= '" + start + "'";
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    // ── Query execution ───────────────────────────────────────────────────────

    private List<Map<String, Object>> safeExecute(String sql, TableContext ctx) {
        return queryExecutor.execute(sql, "KPI", ctx);
    }
}
