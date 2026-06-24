package com.example.BACKEND.catalogue.decision.validation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate validation report produced by {@link ExecutionValidationHarness}.
 *
 * Summary fields are computed from all {@link ExecutionInspectionLog}s.
 * The individual logs contain per-stage details for each test case.
 */
public record ValidationReport(
        int                             totalTests,
        int                             passed,
        int                             failed,
        double                          passRate,              // 0.0–1.0
        Map<String, Integer>            failuresByCategory,    // intent → failure count
        Map<String, Integer>            failuresByStage,       // stage name → failure count
        List<ExecutionInspectionLog>    logs,
        List<String>                    criticalGaps,          // high-priority failures to fix
        Instant                         generatedAt
) {
    /** Compact per-test summary for quick scanning without reading full logs. */
    public record TestSummary(
            String  testId,
            String  question,
            boolean passed,
            int     assertionsFailed,
            String  primaryFailure
    ) {}
}
