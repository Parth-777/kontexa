package com.example.BACKEND.catalogue.decision.compute;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricPackExecutionPlan;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.QueryExecutionDebugger;
import com.example.BACKEND.identity.auth.TenantAccessGuard;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenant.TenantCloudConnectionService.SnowflakeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes decision-runtime {@link QuerySpec}s against Snowflake.
 * Generic SELECT runner — no question or reasoning logic.
 */
@Component
public class SnowflakeWarehouseExecutor implements WarehouseExecutor {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeWarehouseExecutor.class);
    private static final int MAX_ROWS_PER_QUERY = 500;

    private final SnowflakeConnectorService connector;
    private final TenantCloudConnectionService connectionService;
    private final QueryExecutionDebugger sqlDebugger;

    public SnowflakeWarehouseExecutor(
            SnowflakeConnectorService connector,
            TenantCloudConnectionService connectionService,
            QueryExecutionDebugger sqlDebugger
    ) {
        this.connector = connector;
        this.connectionService = connectionService;
        this.sqlDebugger = sqlDebugger;
    }

    @Override
    public ComputationResultSet execute(MetricPackExecutionPlan plan, String tenantId) {
        TenantAccessGuard.assertTenantMatchesAuthContext(tenantId);

        SnowflakeConfig cfg = connectionService.getSnowflakeConfig(tenantId)
                .orElseThrow(() -> new ComputeException("No Snowflake connection for tenant: " + tenantId));

        List<QueryResult> results = new ArrayList<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        int succeeded = 0, failed = 0;

        for (QuerySpec spec : plan.querySpecs()) {
            long start = System.currentTimeMillis();
            String metric = param(spec, "metric");
            String dimension = param(spec, "dimension");
            try {
                String sql = limitedSql(spec.sql());
                List<Map<String, Object>> rows = connector.executeSelect(
                        cfg.account(),
                        cfg.warehouse(),
                        cfg.database(),
                        cfg.schema(),
                        cfg.username(),
                        cfg.password(),
                        sql
                );
                long elapsed = System.currentTimeMillis() - start;
                results.add(new QueryResult(spec.key(), rows, elapsed));
                sqlDebugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        null, spec.key(), sql, rows.size(), true, elapsed,
                        metric, dimension, null), rows);
                log.info("[sf-exec] key={} row_count={} ms={}", spec.key(), rows.size(), elapsed);
                succeeded++;
            } catch (Exception ex) {
                long elapsed = System.currentTimeMillis() - start;
                String sql = spec.sql() != null ? limitedSql(spec.sql()) : "";
                sqlDebugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        null, spec.key(), sql, 0, false, elapsed,
                        metric, dimension, ex.getMessage()));
                log.warn("[compute] snowflake query={} FAILED ms={} error={}",
                        spec.key(), elapsed, ex.getMessage());
                meta.put("error." + spec.key(), ex.getMessage());
                meta.put("failed_sql." + spec.key(), sql);
                failed++;
            }
        }

        meta.put("succeeded", succeeded);
        meta.put("failed", failed);
        meta.put("totalQueries", plan.querySpecs().size());
        return new ComputationResultSet(UUID.randomUUID(), results, meta);
    }

    private static String param(QuerySpec spec, String key) {
        if (spec.params() == null) return "";
        Object v = spec.params().get(key);
        return v != null ? String.valueOf(v) : "";
    }

    private String limitedSql(String sql) {
        String upper = sql.toUpperCase();
        if (upper.contains("LIMIT")) return sql;
        return sql + " LIMIT " + MAX_ROWS_PER_QUERY;
    }
}
