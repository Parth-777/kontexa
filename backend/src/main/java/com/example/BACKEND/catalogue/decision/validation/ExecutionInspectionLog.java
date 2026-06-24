package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.execution.semantic.AnalyticalDecomposition;
import com.example.BACKEND.catalogue.decision.execution.semantic.AnalyticalExecutionPlan;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.ResolvedSchema;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.SemanticRole;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full per-test inspection log for the {@link ExecutionValidationHarness}.
 *
 * Persists every stage's output so failures can be traced to the exact component.
 * This is the primary debugging artifact for the execution validation phase.
 */
public record ExecutionInspectionLog(
        String                      testCaseId,
        String                      question,
        String                      intentCategory,

        // Stage 1: schema resolution
        Map<String, SemanticRole>   resolvedColumnRoles,
        int                         timeDimensionsFound,
        int                         valueMetricsFound,
        int                         volumeMetricsFound,
        int                         entityDimensionsFound,
        boolean                     schemaResolutionSufficient,

        // Stage 2: decomposition
        String                      resolvedIntentType,
        int                         groupingDimensionsProduced,
        int                         metricsProduced,
        int                         derivedMetricsProduced,
        boolean                     temporalDerivationPresent,
        List<String>                groupingDimensionLabels,
        List<String>                metricAliases,

        // Stage 3: plan compilation
        int                         compiledStepCount,
        List<String>                stepTypes,

        // Stage 4: SQL generation
        List<String>                generatedSqlStatements,
        int                         totalSqlGenerated,

        // Stage 5: assertion results
        List<AssertionResult>       assertionResults,
        int                         assertionsPassed,
        int                         assertionsFailed,

        // Stage failures
        List<StageFailure>          stageFailures,
        boolean                     overallPassed,
        Instant                     executedAt
) {

    /** Result of a single SQL assertion check. */
    public record AssertionResult(
            String  assertionId,
            String  description,
            boolean passed,
            String  matchedSql,    // which SQL statement matched (null if not found)
            String  failureReason
    ) {}

    /**
     * Describes which stage in the pipeline failed and why.
     * The harness must never silently swallow failures.
     */
    public record StageFailure(
            String stageName,
            String failureType,    // DECOMPOSITION_GAP, NO_GROUPING, NO_TIME_DERIVATION, etc.
            String detail
    ) {}
}
