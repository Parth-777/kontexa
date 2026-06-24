package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ScaleAwareQueryExecutor {

    private final WarehouseQueryGuard guard;
    private final BigQueryConnectorService bigQueryConnectorService;
    private final SnowflakeConnectorService snowflakeConnectorService;
    private final ScaleProperties properties;

    public ScaleAwareQueryExecutor(
            WarehouseQueryGuard guard,
            BigQueryConnectorService bigQueryConnectorService,
            SnowflakeConnectorService snowflakeConnectorService,
            ScaleProperties properties
    ) {
        this.guard = guard;
        this.bigQueryConnectorService = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.properties = properties;
    }

    /**
     * Execute guarded SQL; returns empty list on rejection or budget exceeded.
     */
    public List<Map<String, Object>> execute(String sql, String label, TableContext ctx) {
        AnalysisRunContext run = ctx.runContext();
        if (run != null && !run.canRunQuery()) {
            System.out.printf("[Scale] Skipping query (budget) [%s]%n", label);
            return List.of();
        }

        try {
            String safeSql = guard.prepare(sql, ctx);
            long estimatedBytes = 0;

            if (ctx.useBQ() && ctx.bqCfg().isPresent()) {
                var c = ctx.bqCfg().get();
                estimatedBytes = bigQueryConnectorService.estimateQueryBytes(
                        c.projectId(), c.serviceAccountJson(), c.location(), c.dataset(), safeSql);
                if (properties.isEnabled() && estimatedBytes > properties.getGuardBigqueryMaxBytesPerQuery()) {
                    throw new QueryRejectedException(
                            "Query exceeds per-query byte cap: " + estimatedBytes);
                }
            }

            List<Map<String, Object>> rows = runWarehouse(safeSql, ctx);
            rows = truncate(rows);

            if (run != null) {
                run.recordQuery(estimatedBytes);
            }

            return rows;

        } catch (QueryRejectedException e) {
            System.out.printf("[Scale] Rejected [%s]: %s%n", label, e.getMessage());
            return List.of();
        } catch (Exception e) {
            System.out.printf("[Scale] Failed [%s]: %s%n", label, e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> runWarehouse(String sql, TableContext ctx) {
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

    private List<Map<String, Object>> truncate(List<Map<String, Object>> rows) {
        int max = properties.getGuardMaxResultRows();
        if (rows == null) return List.of();
        if (rows.size() <= max) return rows;
        return new ArrayList<>(rows.subList(0, max));
    }
}
