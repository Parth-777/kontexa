package com.example.BACKEND.catalogue.decision.runtime;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Manages valid state/stage transitions for a {@link ExecutionRun}.
 * Keeps orchestration logic out of the main runtime loop.
 */
@Component
public class ExecutionLifecycleManager {

    public ExecutionRun start(DecisionExecutionContext ctx) {
        return new ExecutionRun(
                ctx.runId(),
                ctx.tenantId(),
                ExecutionState.RUNNING,
                ExecutionStage.INTENT_RESOLUTION,
                Instant.now(),
                null,
                null
        );
    }

    public ExecutionRun transition(ExecutionRun run, ExecutionStage nextStage) {
        assertRunning(run);
        return new ExecutionRun(
                run.runId(),
                run.tenantId(),
                ExecutionState.RUNNING,
                nextStage,
                run.startedAt(),
                null,
                null
        );
    }

    public ExecutionRun complete(ExecutionRun run) {
        return new ExecutionRun(
                run.runId(),
                run.tenantId(),
                ExecutionState.COMPLETED,
                ExecutionStage.COMPLETED,
                run.startedAt(),
                Instant.now(),
                null
        );
    }

    public ExecutionRun fail(ExecutionRun run, String errorMessage) {
        return new ExecutionRun(
                run.runId(),
                run.tenantId(),
                ExecutionState.FAILED,
                run.stage(),
                run.startedAt(),
                Instant.now(),
                errorMessage
        );
    }

    public ExecutionRun cancel(ExecutionRun run) {
        return new ExecutionRun(
                run.runId(),
                run.tenantId(),
                ExecutionState.CANCELLED,
                run.stage(),
                run.startedAt(),
                Instant.now(),
                "Cancelled by request"
        );
    }

    private void assertRunning(ExecutionRun run) {
        if (run.state() != ExecutionState.RUNNING) {
            throw new DecisionRuntimeException(
                    "Cannot transition run " + run.runId() + ": state is " + run.state());
        }
    }
}
