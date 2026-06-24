package com.example.BACKEND.catalogue.decision.execution.repair;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTraceStep;

import java.util.List;
import java.util.Map;

/**
 * Result of the query repair loop — final rows plus full execution observability.
 */
public record RepairOutcome(
        QueryResult                    result,
        String                         winningStrategy,
        int                            totalAttempts,
        List<ExecutionDiagnostics>     attempts,
        List<ExecutionTraceStep>       traceSteps,
        boolean                        repaired
) {
    public static RepairOutcome empty(String key) {
        return new RepairOutcome(
                new QueryResult(key, List.of(), 0),
                "none", 0, List.of(), List.of(), false);
    }

    public Map<String, Object> toSummary() {
        return Map.of(
                "winning_strategy", winningStrategy != null ? winningStrategy : "none",
                "total_attempts", totalAttempts,
                "repaired", repaired,
                "final_row_count", result != null && result.rows() != null ? result.rows().size() : 0,
                "attempts", attempts.stream().map(ExecutionDiagnostics::toMap).toList()
        );
    }
}
