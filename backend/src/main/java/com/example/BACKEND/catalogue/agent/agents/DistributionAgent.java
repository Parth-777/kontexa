package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Understands the shape of categorical and time-based data.
 *
 * Works on ANY table — tables don't need numeric columns.
 * This agent is what makes Kontexa work on retail, HR, CRM, and any non-numeric dataset.
 *
 * For each dimension column it produces:
 *   - A top-N frequency distribution (e.g. top product categories by record count)
 *   - For the top 3 dimensions only, to keep the prompt manageable
 *
 * For the table's date column it produces:
 *   - A monthly record-volume distribution (how many rows per month)
 *     This reveals seasonality, data gaps, and growth patterns without any numeric columns.
 *
 * Output labels: "Distribution: {col}", "Volume over time"
 */
@Service
public class DistributionAgent {

    private static final int TOP_N       = 15;
    private static final int MAX_DIMS    =  5;
    private static final int TIME_MONTHS = 24;

    private final BigQueryConnectorService  bigQueryConnectorService;
    private final SnowflakeConnectorService snowflakeConnectorService;

    public DistributionAgent(BigQueryConnectorService  bigQueryConnectorService,
                             SnowflakeConnectorService snowflakeConnectorService) {
        this.bigQueryConnectorService  = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
    }

    public List<CollectedData> collectData(TableContext ctx) {
        List<CollectedData> results = new ArrayList<>();

        List<String> dims  = limit(ctx.hints().stringCols(), MAX_DIMS);
        String       dateCol = ctx.hints().dateCol();

        // Category distributions
        for (String dim : dims) {
            String sql = buildCatSql(ctx.tableRef(), dim, ctx.provider());
            safeExecute(sql, "Distribution: " + dim, ctx, results);
        }

        // Volume-over-time (how many rows exist per month)
        if (dateCol != null) {
            String sql = buildTimeSql(ctx.tableRef(), dateCol, ctx.provider());
            safeExecute(sql, "Volume over time", ctx, results);
        }

        return results;
    }

    // ── SQL builders ─────────────────────────────────────────────────────────

    private String buildCatSql(String tableRef, String col, String provider) {
        boolean isBQ   = "bigquery".equalsIgnoreCase(provider);
        String  colRef = isBQ ? "`" + col + "`" : col;
        return String.format(
                "SELECT %s, COUNT(*) AS count FROM %s WHERE %s IS NOT NULL " +
                "GROUP BY %s ORDER BY count DESC LIMIT %d",
                colRef, tableRef, colRef, colRef, TOP_N);
    }

    private String buildTimeSql(String tableRef, String dateCol, String provider) {
        boolean isBQ    = "bigquery".equalsIgnoreCase(provider);
        boolean isSF    = "snowflake".equalsIgnoreCase(provider);
        String  dateRef = isBQ ? "`" + dateCol + "`" : dateCol;

        String trunc;
        if (isBQ) trunc = "DATE_TRUNC(" + dateRef + ", MONTH)";
        else if (isSF) trunc = "DATE_TRUNC('MONTH', " + dateRef + ")";
        else trunc = "DATE_TRUNC('month', " + dateRef + "::date)";

        return String.format(
                "SELECT %s AS month, COUNT(*) AS records FROM %s " +
                "WHERE %s IS NOT NULL GROUP BY 1 ORDER BY 1 DESC LIMIT %d",
                trunc, tableRef, dateRef, TIME_MONTHS);
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
            System.out.printf("[DistributionAgent] Query failed [%s]: %s%n", label, e.getMessage());
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
