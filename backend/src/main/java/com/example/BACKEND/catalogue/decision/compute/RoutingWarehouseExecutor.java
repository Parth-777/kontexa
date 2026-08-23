package com.example.BACKEND.catalogue.decision.compute;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricPackExecutionPlan;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Provider-aware WarehouseExecutor for DecisionRuntime.
 * Branches only here — using existing getProvider() (newest CONNECTED connector).
 */
@Component
@Primary
public class RoutingWarehouseExecutor implements WarehouseExecutor {

    private final BigQueryWarehouseExecutor bigQueryExecutor;
    private final SnowflakeWarehouseExecutor snowflakeExecutor;
    private final TenantCloudConnectionService connections;

    public RoutingWarehouseExecutor(
            BigQueryWarehouseExecutor bigQueryExecutor,
            SnowflakeWarehouseExecutor snowflakeExecutor,
            TenantCloudConnectionService connections
    ) {
        this.bigQueryExecutor = bigQueryExecutor;
        this.snowflakeExecutor = snowflakeExecutor;
        this.connections = connections;
    }

    @Override
    public ComputationResultSet execute(MetricPackExecutionPlan plan, String tenantId) {
        String provider = connections.getProvider(tenantId);
        if (provider == null) {
            provider = "";
        }
        return switch (provider.toLowerCase().trim()) {
            case "snowflake" -> snowflakeExecutor.execute(plan, tenantId);
            case "bigquery", "" -> bigQueryExecutor.execute(plan, tenantId);
            default -> throw new ComputeException(
                    "Unsupported warehouse provider for decision execution: " + provider);
        };
    }
}
