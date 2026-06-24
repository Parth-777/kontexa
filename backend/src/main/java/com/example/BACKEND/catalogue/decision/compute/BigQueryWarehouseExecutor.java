package com.example.BACKEND.catalogue.decision.compute;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.QueryExecutionDebugger;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenant.TenantCloudConnectionService.BigQueryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes decision-runtime {@link QuerySpec}s against BigQuery.
 *
 * Wraps the existing {@link BigQueryConnectorService} — no new BQ client code.
 * Skips failed queries gracefully rather than aborting the whole plan.
 */
@Component
public class BigQueryWarehouseExecutor implements WarehouseExecutor {

    private static final Logger log = LoggerFactory.getLogger(BigQueryWarehouseExecutor.class);
    private static final int    MAX_ROWS_PER_QUERY = 500;

    private final BigQueryConnectorService      connector;
    private final TenantCloudConnectionService  connectionService;
    private final QueryExecutionDebugger        sqlDebugger;

    public BigQueryWarehouseExecutor(
            BigQueryConnectorService     connector,
            TenantCloudConnectionService connectionService,
            QueryExecutionDebugger     sqlDebugger
    ) {
        this.connector         = connector;
        this.connectionService = connectionService;
        this.sqlDebugger       = sqlDebugger;
    }

    @Override
    public ComputationResultSet execute(MetricPackExecutionPlan plan, String tenantId) {
        BigQueryConfig cfg = connectionService.getBigQueryConfig(tenantId)
                .orElseThrow(() -> new ComputeException("No BigQuery connection for tenant: " + tenantId));

        List<QueryResult> results  = new ArrayList<>();
        Map<String, Object> meta   = new LinkedHashMap<>();
        int succeeded = 0, failed = 0;

        for (QuerySpec spec : plan.querySpecs()) {
            long start = System.currentTimeMillis();
            String metric = param(spec, "metric");
            String dimension = param(spec, "dimension");
            try {
                String sql = limitedSql(spec.sql());
                List<Map<String, Object>> rows = connector.executeSelect(
                        cfg.projectId(),
                        cfg.serviceAccountJson(),
                        cfg.location(),
                        cfg.dataset(),
                        sql
                );
                long elapsed = System.currentTimeMillis() - start;
                results.add(new QueryResult(spec.key(), rows, elapsed));
                sqlDebugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        null, spec.key(), sql, rows.size(), true, elapsed,
                        metric, dimension, null), rows);
                log.info("[bq-exec] key={} row_count={} ms={}", spec.key(), rows.size(), elapsed);
                log.info("[bq-exec] key={} sql={}", spec.key(), sql);
                log.info("[bq-exec] key={} sample_rows={}", spec.key(),
                        QueryExecutionDebugger.firstRows(rows, 20));
                succeeded++;
            } catch (Exception ex) {
                long elapsed = System.currentTimeMillis() - start;
                String sql = spec.sql() != null ? limitedSql(spec.sql()) : "";
                sqlDebugger.log(new QueryExecutionDebugger.ExecutionRecord(
                        null, spec.key(), sql, 0, false, elapsed,
                        metric, dimension, ex.getMessage()));
                log.warn("[compute] query={} FAILED ms={} error={} sql={}",
                        spec.key(), elapsed, ex.getMessage(), sql);
                meta.put("error." + spec.key(), ex.getMessage());
                meta.put("failed_sql." + spec.key(), sql);
                failed++;
            }
        }

        meta.put("succeeded", succeeded);
        meta.put("failed", failed);
        meta.put("totalQueries", plan.querySpecs().size());
        return new ComputationResultSet(plan.querySpecs().isEmpty() ? UUID.randomUUID()
                : UUID.randomUUID(), results, meta);
    }

    private static String param(QuerySpec spec, String key) {
        if (spec.params() == null) return "";
        Object v = spec.params().get(key);
        return v != null ? String.valueOf(v) : "";
    }

    /** Inject a LIMIT clause if none is present, to guard against runaway scans. */
    private String limitedSql(String sql) {
        String upper = sql.toUpperCase();
        if (upper.contains("LIMIT")) return sql;
        return sql + " LIMIT " + MAX_ROWS_PER_QUERY;
    }
}
