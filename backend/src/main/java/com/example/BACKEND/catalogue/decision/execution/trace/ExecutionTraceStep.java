package com.example.BACKEND.catalogue.decision.execution.trace;

import java.time.Instant;
import java.util.Map;

/**
 * A single step in the analyst investigation trace shown to users.
 */
public record ExecutionTraceStep(
        String              stepKey,
        String              title,
        StepStatus          status,
        Instant             timestamp,
        Map<String, Object> details,
        long                durationMs
) {
    public enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public static ExecutionTraceStep pending(String key, String title) {
        return new ExecutionTraceStep(key, title, StepStatus.PENDING, Instant.now(), Map.of(), 0);
    }

    public ExecutionTraceStep running(Map<String, Object> details) {
        return new ExecutionTraceStep(stepKey, title, StepStatus.RUNNING, Instant.now(),
                details != null ? details : Map.of(), durationMs);
    }

    public ExecutionTraceStep completed(long ms, Map<String, Object> details) {
        return new ExecutionTraceStep(stepKey, title, StepStatus.COMPLETED, Instant.now(),
                details != null ? details : Map.of(), ms);
    }

    public ExecutionTraceStep failed(long ms, String error) {
        return new ExecutionTraceStep(stepKey, title, StepStatus.FAILED, Instant.now(),
                Map.of("error", error != null ? error : "unknown"), ms);
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "step_key", stepKey,
                "title", title,
                "status", status.name(),
                "timestamp", timestamp.toString(),
                "details", details,
                "duration_ms", durationMs
        );
    }
}
