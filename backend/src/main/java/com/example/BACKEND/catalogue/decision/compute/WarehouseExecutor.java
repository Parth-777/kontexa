package com.example.BACKEND.catalogue.decision.compute;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;

/**
 * Provider-agnostic warehouse execution port.
 *
 * The decision runtime depends ONLY on this interface.
 * Each cloud connector provides a concrete adapter.
 */
public interface WarehouseExecutor {

    /**
     * Execute all {@link QuerySpec}s in the plan and return the full
     * {@link ComputationResultSet}.
     *
     * Implementations must:
     *   - run queries sequentially to avoid saturating tenant quotas
     *   - capture per-query elapsed time
     *   - skip (not fail) individual queries that produce SQL errors,
     *     recording the error in {@link ComputationResultSet#executionMeta()}
     */
    ComputationResultSet execute(MetricPackExecutionPlan plan, String tenantId);
}
