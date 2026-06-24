package com.example.BACKEND.catalogue.agent.agents;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
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
    private static final int TIME_MONTHS = 24;

    private final ScaleAwareQueryExecutor queryExecutor;

    public DistributionAgent(ScaleAwareQueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    public List<CollectedData> collectData(TableContext ctx) {
        List<CollectedData> results = new ArrayList<>();

        List<String> dims  = ctx.hints().stringCols();
        String       dateCol = ctx.hints().dateCol();

        // Category distributions
        for (String dim : dims) {
            String sql = buildCatSql(ctx, dim);
            safeExecute(sql, "Distribution: " + dim, ctx, results);
        }

        // Volume-over-time (how many rows exist per month)
        if (dateCol != null) {
            String sql = buildTimeSql(ctx, dateCol);
            safeExecute(sql, "Volume over time", ctx, results);
        }

        return results;
    }

    // ── SQL builders ─────────────────────────────────────────────────────────

    private String buildCatSql(TableContext ctx, String col) {
        String colRef = AgentSqlHelper.qualify(col, ctx.provider());
        return String.format(
                "SELECT %s, COUNT(*) AS count FROM %s GROUP BY %s ORDER BY count DESC LIMIT %d",
                colRef, AgentSqlHelper.fromWithPredicates(ctx, colRef + " IS NOT NULL"), colRef, TOP_N);
    }

    private String buildTimeSql(TableContext ctx, String dateCol) {
        String provider = ctx.provider();
        String dateRef = AgentSqlHelper.qualify(dateCol, provider);
        String trunc = dateTruncMonthly(dateRef, provider);
        return String.format(
                "SELECT %s AS month, COUNT(*) AS records FROM %s GROUP BY 1 ORDER BY 1 DESC LIMIT %d",
                trunc, AgentSqlHelper.fromWithPredicates(ctx, dateRef + " IS NOT NULL"), TIME_MONTHS);
    }

    private String dateTruncMonthly(String dateRef, String provider) {
        return AgentSqlHelper.dateTruncMonth(dateRef, provider);
    }

    // ── Query execution ───────────────────────────────────────────────────────

    private void safeExecute(String sql, String label, TableContext ctx, List<CollectedData> out) {
        List<Map<String, Object>> rows = queryExecutor.execute(sql, label, ctx);
        if (rows != null && !rows.isEmpty()) {
            out.add(new CollectedData(label, sql, rows));
        }
    }
}
