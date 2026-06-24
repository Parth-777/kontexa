package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.execution.semantic.*;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.ResolvedColumn;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.ResolvedSchema;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.SemanticRole;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalAssertionChecker.CheckResult;
import com.example.BACKEND.catalogue.decision.validation.ExecutionInspectionLog.StageFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Execution Validation Harness.
 *
 * Runs the full pre-warehouse analytical pipeline against the {@link GoldenTestSuite}
 * and produces a structured {@link ValidationReport}.
 *
 * Pipeline stages exercised (no warehouse required):
 *
 *   1. SCHEMA_RESOLUTION  — SchemaSemanticResolver maps columns to semantic roles
 *   2. DECOMPOSITION      — AnalyticalQueryDecomposer produces grouping/metric/ranking targets
 *   3. PLAN_COMPILATION   — AnalyticalPlanCompiler produces ComputationSteps
 *   4. SQL_GENERATION     — AnalyticalSQLGenerator produces SQL strings
 *   5. ASSERTION_CHECK    — AnalyticalAssertionChecker validates SQL against all assertions
 *
 * Failures are never swallowed. Each stage records what went wrong and WHY.
 * A test fails if:
 *   - Any MUST_CONTAIN assertion is not satisfied
 *   - Any structural gap is detected (no GROUP BY, no aggregate, fallback-only SQL)
 *
 * This harness does NOT call LLM, does NOT connect to a warehouse.
 * It validates that correct analytical SQL is generated from schema + intent alone.
 */
@Service
public class ExecutionValidationHarness {

    private static final Logger log = LoggerFactory.getLogger(ExecutionValidationHarness.class);

    private final SchemaSemanticResolver    schemaResolver;
    private final AnalyticalQueryDecomposer decomposer;
    private final AnalyticalPlanCompiler    compiler;
    private final AnalyticalSQLGenerator    sqlGenerator;
    private final MockRegistryBuilder       registryBuilder;
    private final AnalyticalAssertionChecker assertionChecker;

    public ExecutionValidationHarness(
            SchemaSemanticResolver    schemaResolver,
            AnalyticalQueryDecomposer decomposer,
            AnalyticalPlanCompiler    compiler,
            AnalyticalSQLGenerator    sqlGenerator,
            MockRegistryBuilder       registryBuilder,
            AnalyticalAssertionChecker assertionChecker
    ) {
        this.schemaResolver   = schemaResolver;
        this.decomposer       = decomposer;
        this.compiler         = compiler;
        this.sqlGenerator     = sqlGenerator;
        this.registryBuilder  = registryBuilder;
        this.assertionChecker = assertionChecker;
    }

    // ─── public API ──────────────────────────────────────────────────────

    /** Run the full golden test suite and return a complete validation report. */
    public ValidationReport runFullSuite() {
        return run(GoldenTestSuite.all());
    }

    /** Run a filtered subset by intent category. */
    public ValidationReport runByCategory(String category) {
        List<AnalyticalTestCase> filtered = GoldenTestSuite.all().stream()
                .filter(tc -> category.equalsIgnoreCase(tc.intentCategory()))
                .collect(Collectors.toList());
        return run(filtered);
    }

    /** Run a single test case by ID. */
    public Optional<ExecutionInspectionLog> runSingle(String testCaseId) {
        return GoldenTestSuite.all().stream()
                .filter(tc -> tc.id().equals(testCaseId))
                .findFirst()
                .map(this::executeTestCase);
    }

    // ─── core execution ───────────────────────────────────────────────────

    private ValidationReport run(List<AnalyticalTestCase> cases) {
        log.info("[validation] starting run: {} test cases", cases.size());

        List<ExecutionInspectionLog> logs = cases.stream()
                .map(this::executeTestCase)
                .collect(Collectors.toList());

        int passed = (int) logs.stream().filter(ExecutionInspectionLog::overallPassed).count();
        int failed = logs.size() - passed;
        double passRate = logs.isEmpty() ? 0.0 : (double) passed / logs.size();

        Map<String, Integer> failuresByCategory = new LinkedHashMap<>();
        Map<String, Integer> failuresByStage    = new LinkedHashMap<>();

        for (ExecutionInspectionLog l : logs) {
            if (!l.overallPassed()) {
                failuresByCategory.merge(l.intentCategory(), 1, Integer::sum);
                l.stageFailures().forEach(sf ->
                        failuresByStage.merge(sf.stageName(), 1, Integer::sum));
            }
        }

        List<String> criticalGaps = identifyCriticalGaps(logs);

        log.info("[validation] complete — passed={} failed={} passRate={}%",
                passed, failed, String.format("%.1f", passRate * 100));

        return new ValidationReport(cases.size(), passed, failed, passRate,
                failuresByCategory, failuresByStage, logs, criticalGaps, Instant.now());
    }

    private ExecutionInspectionLog executeTestCase(AnalyticalTestCase tc) {
        log.debug("[validation] running test: {} — {}", tc.id(), tc.question());

        List<StageFailure> stageFailures = new ArrayList<>();
        Map<String, SemanticRole> columnRoles = new LinkedHashMap<>();

        // ── Stage 1: Schema resolution ─────────────────────────────────────
        RegistryResolutionBundle bundle = registryBuilder.buildBundle(tc.schema());
        ResolvedSchema resolvedSchema;
        try {
            resolvedSchema = schemaResolver.resolve(bundle, tc.schema().tableRef());
            resolvedSchema.columns().forEach(c ->
                    columnRoles.put(c.columnName(), c.role()));
        } catch (Exception e) {
            stageFailures.add(new StageFailure("SCHEMA_RESOLUTION", "EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            return buildFailedLog(tc, columnRoles, stageFailures, List.of());
        }

        boolean schemaOk = resolvedSchema.has(SemanticRole.VALUE_METRIC)
                || resolvedSchema.has(SemanticRole.VOLUME_METRIC);
        if (!schemaOk) {
            stageFailures.add(new StageFailure("SCHEMA_RESOLUTION", "NO_METRICS_RESOLVED",
                    "Schema resolution found no VALUE_METRIC or VOLUME_METRIC. " +
                    "All columns were classified as IDENTIFIER, UNKNOWN, or ENTITY_DIMENSION. " +
                    "Column types: " + tc.schema().columns().stream()
                            .map(c -> c.name() + "=" + c.type()).collect(Collectors.joining(", "))));
        }

        // ── Stage 2: Decomposition ─────────────────────────────────────────
        InvestigationPlan plan = registryBuilder.buildPlan(tc);
        AnalyticalDecomposition decomposition;
        try {
            decomposition = decomposer.decompose(plan, resolvedSchema);
        } catch (Exception e) {
            stageFailures.add(new StageFailure("DECOMPOSITION", "EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            return buildFailedLog(tc, columnRoles, stageFailures, List.of());
        }

        if (decomposition.groupingDimensions().isEmpty()) {
            stageFailures.add(new StageFailure("DECOMPOSITION", "NO_GROUPING_DIMENSIONS",
                    "Decomposition produced 0 grouping dimensions. " +
                    "Schema ENTITY_DIMENSION count=" + resolvedSchema.byRole(SemanticRole.ENTITY_DIMENSION).size()
                    + " TIME_DIMENSION count=" + resolvedSchema.byRole(SemanticRole.TIME_DIMENSION).size()
                    + " for table=" + tc.schema().tableRef()));
        }

        if (decomposition.metrics().isEmpty()) {
            stageFailures.add(new StageFailure("DECOMPOSITION", "NO_METRICS_DECOMPOSED",
                    "Decomposition produced 0 metric targets — no SQL aggregates will be generated."));
        }

        // ── Stage 3: Plan compilation ──────────────────────────────────────
        AnalyticalExecutionPlan executionPlan;
        try {
            executionPlan = compiler.compile(decomposition);
        } catch (Exception e) {
            stageFailures.add(new StageFailure("PLAN_COMPILATION", "EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            return buildFailedLog(tc, columnRoles, stageFailures, List.of());
        }

        if (executionPlan.steps().isEmpty()) {
            stageFailures.add(new StageFailure("PLAN_COMPILATION", "NO_STEPS_COMPILED",
                    "Plan compilation produced 0 computation steps."));
        }

        // ── Stage 4: SQL generation ────────────────────────────────────────
        List<QuerySpec> querySpecs;
        try {
            querySpecs = sqlGenerator.generate(executionPlan);
        } catch (Exception e) {
            stageFailures.add(new StageFailure("SQL_GENERATION", "EXCEPTION",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            return buildFailedLog(tc, columnRoles, stageFailures, List.of());
        }

        List<String> sqlStatements = querySpecs.stream()
                .map(QuerySpec::sql)
                .collect(Collectors.toList());

        // ── Stage 5: Assertion checking ────────────────────────────────────
        CheckResult checkResult = assertionChecker.check(tc, sqlStatements);
        stageFailures.addAll(checkResult.failures());

        boolean overallPassed = stageFailures.isEmpty();

        if (overallPassed) {
            log.debug("[validation] PASS: {} — {} SQL stmts, {} assertions",
                    tc.id(), sqlStatements.size(), checkResult.passed());
        } else {
            log.warn("[validation] FAIL: {} [{}] — {} stage failures, {} assertion failures",
                    tc.id(), tc.question(),
                    stageFailures.stream().map(StageFailure::stageName).distinct().count(),
                    checkResult.failed());
            stageFailures.forEach(sf ->
                    log.warn("[validation]   ↳ [{}] {} — {}", sf.stageName(), sf.failureType(), sf.detail()));
        }

        List<String> stepTypes = executionPlan.steps().stream()
                .map(s -> s.stepType().name())
                .collect(Collectors.toList());

        List<String> groupingLabels = decomposition.groupingDimensions().stream()
                .map(d -> d.displayLabel() + (d.derived() ? " [DERIVED]" : ""))
                .collect(Collectors.toList());

        List<String> metricAliases = decomposition.metrics().stream()
                .map(m -> m.alias() + " = " + m.aggregation() + "(" + m.columnName() + ")")
                .collect(Collectors.toList());

        return new ExecutionInspectionLog(
                tc.id(), tc.question(), tc.intentCategory(),
                columnRoles,
                resolvedSchema.byRole(SemanticRole.TIME_DIMENSION).size(),
                resolvedSchema.byRole(SemanticRole.VALUE_METRIC).size(),
                resolvedSchema.byRole(SemanticRole.VOLUME_METRIC).size(),
                resolvedSchema.byRole(SemanticRole.ENTITY_DIMENSION).size(),
                schemaOk,
                decomposition.intentType().name(),
                decomposition.groupingDimensions().size(),
                decomposition.metrics().size(),
                decomposition.derivedMetrics().size(),
                decomposition.temporalSpec().hasTemporalData(),
                groupingLabels,
                metricAliases,
                executionPlan.steps().size(),
                stepTypes,
                sqlStatements,
                sqlStatements.size(),
                checkResult.results(),
                checkResult.passed(),
                checkResult.failed(),
                stageFailures,
                overallPassed,
                Instant.now()
        );
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private ExecutionInspectionLog buildFailedLog(
            AnalyticalTestCase tc,
            Map<String, SemanticRole> columnRoles,
            List<StageFailure> failures,
            List<String> sqls
    ) {
        return new ExecutionInspectionLog(
                tc.id(), tc.question(), tc.intentCategory(),
                columnRoles, 0, 0, 0, 0, false,
                "UNKNOWN", 0, 0, 0, false,
                List.of(), List.of(), 0, List.of(),
                sqls, sqls.size(),
                List.of(), 0, 0,
                failures, false, Instant.now()
        );
    }

    private List<String> identifyCriticalGaps(List<ExecutionInspectionLog> logs) {
        Map<String, Long> failureTypeCount = logs.stream()
                .flatMap(l -> l.stageFailures().stream())
                .collect(Collectors.groupingBy(StageFailure::failureType, Collectors.counting()));

        List<String> gaps = new ArrayList<>();

        failureTypeCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> gaps.add(
                        e.getKey() + " — " + e.getValue() + " test(s) affected"
                ));

        return gaps;
    }
}
