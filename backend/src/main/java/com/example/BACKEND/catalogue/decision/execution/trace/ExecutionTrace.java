package com.example.BACKEND.catalogue.decision.execution.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full execution trace for one analytical request.
 */
public record ExecutionTrace(
        UUID                    traceId,
        List<ExecutionTraceStep> steps,
        Instant                 startedAt,
        Instant                 completedAt
) {
    public static ExecutionTrace start() {
        return new ExecutionTrace(UUID.randomUUID(), new ArrayList<>(), Instant.now(), null);
    }

    public ExecutionTrace withStep(ExecutionTraceStep step) {
        List<ExecutionTraceStep> next = new ArrayList<>(steps);
        int idx = indexOf(step.stepKey());
        if (idx >= 0) next.set(idx, step);
        else next.add(step);
        return new ExecutionTrace(traceId, List.copyOf(next), startedAt, completedAt);
    }

    public ExecutionTrace complete() {
        return new ExecutionTrace(traceId, steps, startedAt, Instant.now());
    }

    private int indexOf(String key) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepKey().equals(key)) return i;
        }
        return -1;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace_id", traceId.toString());
        m.put("started_at", startedAt.toString());
        if (completedAt != null) m.put("completed_at", completedAt.toString());
        m.put("steps", steps.stream().map(ExecutionTraceStep::toMap).toList());
        return m;
    }
}
