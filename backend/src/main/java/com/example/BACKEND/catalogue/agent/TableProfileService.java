package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.scale.AgentSqlHelper;
import com.example.BACKEND.catalogue.agent.scale.ScaleAwareQueryExecutor;
import com.example.BACKEND.catalogue.agent.scale.ScaleTier;
import com.example.BACKEND.catalogue.agent.scale.TableScalePolicy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces raw SELECT * samples on MEDIUM/LARGE tables with bounded aggregate profiles.
 */
@Service
public class TableProfileService {

    private static final int TOP_DIM_VALUES = 5;

    private final ScaleAwareQueryExecutor queryExecutor;
    private final TableScalePolicy scalePolicy;

    public TableProfileService(ScaleAwareQueryExecutor queryExecutor, TableScalePolicy scalePolicy) {
        this.queryExecutor = queryExecutor;
        this.scalePolicy = scalePolicy;
    }

    public List<CollectedData> collectProfile(TableContext ctx) {
        if (scalePolicy.allowRawSample(ctx.tier())) {
            return List.of();
        }

        List<CollectedData> out = new ArrayList<>();
        String tableName = ctx.hints().tableName();

        collectSummary(ctx, tableName, out);

        for (String dim : ctx.hints().stringCols()) {
            collectDimensionTopN(ctx, tableName, dim, out);
        }

        return out;
    }

    private void collectSummary(TableContext ctx, String tableName, List<CollectedData> out) {
        String window = AgentSqlHelper.windowClause(ctx);
        String sql;
        if (ctx.hints().dateCol() != null) {
            String dateRef = AgentSqlHelper.qualify(ctx.hints().dateCol(), ctx.provider());
            sql = String.format(
                    "SELECT COUNT(*) AS row_count, MIN(%s) AS min_date, MAX(%s) AS max_date FROM %s",
                    dateRef, dateRef, AgentSqlHelper.qualifiedTableRef(ctx) + window);
        } else {
            sql = String.format("SELECT COUNT(*) AS row_count FROM %s",
                    AgentSqlHelper.qualifiedTableRef(ctx));
        }

        List<Map<String, Object>> rows = queryExecutor.execute(sql, "PROFILE: summary " + tableName, ctx);
        if (!rows.isEmpty()) {
            out.add(new CollectedData("PROFILE: " + tableName + " summary", sql, rows));
        }
    }

    private void collectDimensionTopN(TableContext ctx, String tableName, String dim,
                                       List<CollectedData> out) {
        String dimRef = AgentSqlHelper.qualify(dim, ctx.provider());
        String from = AgentSqlHelper.fromWithPredicates(ctx, dimRef + " IS NOT NULL");
        String sql = String.format(
                "SELECT %s AS dimension_value, COUNT(*) AS count FROM %s " +
                "GROUP BY %s ORDER BY count DESC LIMIT %d",
                dimRef, from, dimRef, TOP_DIM_VALUES);

        List<Map<String, Object>> rows = queryExecutor.execute(
                sql, "PROFILE: " + tableName + " top " + dim, ctx);
        if (!rows.isEmpty()) {
            out.add(new CollectedData("PROFILE: " + tableName + " by " + dim, sql, rows));
        }
    }
}
