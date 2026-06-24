package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfiler;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalQueryMaterializer;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.presentation.MetricBucketingEngine;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import com.example.BACKEND.catalogue.decision.execution.materialization.DerivedDimensionMaterializer;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupByExecutor;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationPlanBuilder;
import com.example.BACKEND.catalogue.decision.execution.materialization.NumericDimensionBucketer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end validation runner: 112 fresh questions across 8 datasets.
 *
 * Run: ./mvnw test -Dtest=CrossDatasetValidationRunnerTest
 * Log: target/cross-dataset-validation.log
 */
class CrossDatasetValidationRunnerTest {

    private static final Path LOG_FILE = Path.of("target", "cross-dataset-validation.log");
    private static PrintWriter log;

    private QuestionSemanticExtractor extractor;
    private MetricResolutionEngine metricEngine;
    private QuestionInvestigationPlanner investigationPlanner;
    private UniversalAnalysisPlanner analysisPlanner;
    private DeterministicAnalyticalQueryPlanner sqlPlanner;
    private AnalyticalQueryMaterializer materializer;
    private SchemaProfiler schemaProfiler;

    private final Map<String, RegistryResolutionBundle> bundles = new LinkedHashMap<>();

    @BeforeAll
    static void openLog() throws IOException {
        Files.createDirectories(LOG_FILE.getParent());
        log = new PrintWriter(Files.newBufferedWriter(LOG_FILE));
        log.println("Cross-dataset validation — " + java.time.Instant.now());
        log.flush();
    }

    @org.junit.jupiter.api.AfterAll
    static void closeLog() {
        if (log != null) {
            log.flush();
            log.close();
        }
    }

    @BeforeEach
    void setUp() {
        extractor = MetricResolutionTestSupport.extractor();
        metricEngine = MetricResolutionTestSupport.engine();
        investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
        analysisPlanner = UniversalPlannerTestSupport.universalPlanner();
        sqlPlanner = SqlTemplateTestHarness.create().planner;
        materializer = new AnalyticalQueryMaterializer(
                new DerivedDimensionMaterializer(),
                new NumericDimensionBucketer(new MetricBucketingEngine()),
                new MaterializationPlanBuilder(new PresentationLabelResolver()),
                new GroupByExecutor(),
                new PresentationLabelResolver());
        schemaProfiler = new SchemaProfiler();
        bundles.clear();
        for (ValidationDatasetRegistry.DatasetDef def : ValidationDatasetRegistry.all()) {
            bundles.put(def.name(), def.bundle());
        }
    }

    @Test
    void runFreshQuestionValidation() {
        List<ValidationQuestionBank.ValidationCase> cases = ValidationQuestionBank.all();
        assertTrue(cases.size() >= 100, "expected at least 100 validation questions");

        EnumMap<FailureClass, Integer> failureCounts = new EnumMap<>(FailureClass.class);
        for (FailureClass fc : FailureClass.values()) {
            failureCounts.put(fc, 0);
        }
        List<ValidationResult> results = new ArrayList<>();

        for (ValidationQuestionBank.ValidationCase vc : cases) {
            results.add(runCase(vc));
        }

        int passed = 0;
        for (ValidationResult r : results) {
            if (r.failureClass() == FailureClass.NONE) {
                passed++;
            } else {
                failureCounts.merge(r.failureClass(), 1, Integer::sum);
            }
        }

        String summary = buildSummary(cases.size(), passed, failureCounts, results);
        System.out.println(summary);
        if (log != null) {
            log.println(summary);
            log.flush();
        }
    }

    private ValidationResult runCase(ValidationQuestionBank.ValidationCase vc) {
        RegistryResolutionBundle bundle = bundles.get(vc.dataset());
        String question = vc.question();
        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(90)).append('\n');
        out.append("DATASET: ").append(vc.dataset()).append('\n');
        out.append("1. Question: ").append(question).append('\n');

        QuestionSemantics semantics = extractor.extract(question, bundle);
        out.append("2. QuestionSemantics: metric=").append(semantics.primaryMetric())
                .append(" target=").append(semantics.targetMetric())
                .append(" dimension=").append(semantics.dimension())
                .append(" grouping=").append(semantics.grouping())
                .append(" intent=").append(semantics.intent())
                .append(" entities=").append(semantics.extractedEntities()).append('\n');

        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        out.append("3. MetricResolution: metric=").append(resolution.primaryMetric())
                .append(" dimension=").append(resolution.dimension())
                .append(" grouping=").append(resolution.grouping())
                .append(" relationship=").append(resolution.relationshipVariable())
                .append(" rejected=").append(resolution.rejected())
                .append(resolution.rejected() ? " reason=" + resolution.rejectionReason() : "")
                .append(" usable=").append(resolution.isUsable()).append('\n');

        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        out.append("4. Investigation: metric=").append(investigation.extraction().metricKey())
                .append(" dimension=").append(investigation.dimension().resolved()
                        ? investigation.dimension().columnKey() : "unresolved")
                .append(" intent=").append(investigation.extraction().intent())
                .append(" executable=").append(investigation.executable());
        if (!investigation.executable()) {
            out.append(" blocker=").append(investigation.blockingReason());
        }
        out.append('\n');

        AnalysisPlan plan = analysisPlanner.plan(question, bundle, investigation, resolution, List.of());
        out.append("5. AnalysisPlan: intent=").append(plan.intent())
                .append(" metric=").append(plan.primaryMetric())
                .append(" dimension=").append(plan.dimension())
                .append(" grouping=").append(plan.groupingAlias())
                .append(" relationship=").append(plan.relationshipVariable())
                .append(" executable=").append(plan.executable());
        if (!plan.executable()) {
            out.append(" blocker=").append(plan.blockingReason());
        }
        out.append('\n');

        String sql = "";
        String sqlError = null;
        if (plan.executable()) {
            try {
                List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
                sql = specs.stream().map(QuerySpec::sql).collect(Collectors.joining("\n---\n"));
                out.append("6. Generated SQL:\n").append(sql.isBlank() ? "(empty)" : sql).append('\n');
            } catch (Exception ex) {
                sqlError = ex.getMessage();
                out.append("6. Generated SQL: ERROR ").append(sqlError).append('\n');
            }
        } else {
            out.append("6. Generated SQL: skipped (plan not executable)\n");
        }

        String finalAnswer = "(not computed)";
        if (plan.executable() && sqlError == null && !sql.isBlank()) {
            List<Map<String, Object>> rows = syntheticRows(plan);
            var profile = schemaProfiler.profile(rows);
            MaterializedQueryResult materialized = materializer.materialize(rows, profile, null);
            finalAnswer = formatFinalAnswer(materialized);
            out.append("7. Final answer: ").append(finalAnswer).append('\n');
        } else {
            out.append("7. Final answer: skipped\n");
        }

        FailureClass failure = classify(vc, semantics, resolution, plan, sql, sqlError);
        out.append("CLASSIFICATION: ").append(failure.label).append('\n');
        if (failure != FailureClass.NONE) {
            out.append("DETAIL: ").append(failureDetail(vc, semantics, resolution, plan, sql, sqlError)).append('\n');
        }

        String block = out.toString();
        System.out.print(block);
        if (log != null) {
            log.print(block);
            log.flush();
        }

        return new ValidationResult(vc, failure);
    }

    private static FailureClass classify(
            ValidationQuestionBank.ValidationCase vc,
            QuestionSemantics semantics,
            MetricResolution resolution,
            AnalysisPlan plan,
            String sql,
            String sqlError
    ) {
        if (semantics.primaryMetric() == null || semantics.primaryMetric().isBlank()) {
            return FailureClass.SEMANTIC_EXTRACTION;
        }
        if (vc.expectedMetric() != null && !matchesColumn(semantics.primaryMetric(), vc.expectedMetric())) {
            return FailureClass.SEMANTIC_EXTRACTION;
        }
        if (resolution.rejected() || resolution.primaryMetric() == null || resolution.primaryMetric().isBlank()) {
            return FailureClass.METRIC_RESOLUTION;
        }
        if (!resolution.isUsable()) {
            return FailureClass.METRIC_RESOLUTION;
        }
        if (vc.expectedIntent() == AnalysisIntent.RELATIONSHIP) {
            if (plan.relationshipVariable() == null || plan.relationshipVariable().isBlank()) {
                return FailureClass.RELATIONSHIP_SELECTION;
            }
        }
        if (!plan.executable()) {
            if (vc.expectedIntent() != plan.intent()) {
                return FailureClass.INTENT_DETECTION;
            }
            return FailureClass.DIMENSION_SELECTION;
        }
        if (plan.intent() != vc.expectedIntent()) {
            return FailureClass.INTENT_DETECTION;
        }
        if (vc.expectedDimension() != null && vc.expectedIntent() != AnalysisIntent.RELATIONSHIP) {
            if (!matchesColumn(plan.dimension(), vc.expectedDimension())) {
                return FailureClass.DIMENSION_SELECTION;
            }
        }
        if (sqlError != null || sql.isBlank()) {
            return FailureClass.SQL_GENERATION;
        }
        return FailureClass.NONE;
    }

    private static String failureDetail(
            ValidationQuestionBank.ValidationCase vc,
            QuestionSemantics semantics,
            MetricResolution resolution,
            AnalysisPlan plan,
            String sql,
            String sqlError
    ) {
        StringBuilder d = new StringBuilder();
        d.append("expected intent=").append(vc.expectedIntent())
                .append(" metric=").append(vc.expectedMetric())
                .append(" dimension=").append(vc.expectedDimension());
        d.append(" | got semantics metric=").append(semantics.primaryMetric())
                .append(" dimension=").append(semantics.dimension());
        d.append(" | resolution metric=").append(resolution.primaryMetric())
                .append(" rejected=").append(resolution.rejected());
        d.append(" | plan intent=").append(plan.intent())
                .append(" dimension=").append(plan.dimension())
                .append(" relationship=").append(plan.relationshipVariable())
                .append(" executable=").append(plan.executable());
        if (sqlError != null) d.append(" | sqlError=").append(sqlError);
        else if (sql.isBlank()) d.append(" | sql=empty");
        return d.toString();
    }

    private static boolean matchesColumn(String actual, String expected) {
        if (actual == null || expected == null) return false;
        String a = actual.toLowerCase(Locale.ROOT);
        String e = expected.toLowerCase(Locale.ROOT);
        return a.equals(e) || a.startsWith(e + "_") || a.contains(e);
    }

    private static List<Map<String, Object>> syntheticRows(AnalysisPlan plan) {
        if (plan.intent() == AnalysisIntent.RELATIONSHIP) {
            return List.of(Map.of(
                    "correlation_coefficient", 0.35,
                    "row_count", 1200L));
        }
        String dim = plan.dimension() != null ? plan.dimension()
                : (plan.groupingAlias() != null ? plan.groupingAlias() : "segment");
        String metric = plan.primaryMetric() != null ? plan.primaryMetric() : "metric_value";
        if ("composition".equalsIgnoreCase(dim)) {
            return List.of(
                    Map.of("segment", "a", metric, 70.0),
                    Map.of("segment", "b", metric, 30.0));
        }
        return List.of(
                Map.of(dim, "alpha", metric, 100.0),
                Map.of(dim, "beta", metric, 80.0),
                Map.of(dim, "gamma", metric, 55.0));
    }

    private static String formatFinalAnswer(MaterializedQueryResult m) {
        if (m.correlation() != null && m.correlation().isValid()) {
            return m.correlation().interpretation();
        }
        if (m.primaryGrouping() != null && m.primaryGrouping().hasData()) {
            var top = m.primaryGrouping().top(1).getFirst();
            return "Top " + top.entityKey() + ": " + top.totalValue();
        }
        if (m.scalar() != null && m.scalar().isValid()) {
            return m.scalar().metricLabel() + "=" + m.scalar().value();
        }
        return "(no materialized content)";
    }

    private static String buildSummary(
            int total, int passed,
            EnumMap<FailureClass, Integer> failureCounts,
            List<ValidationResult> results
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(90)).append('\n');
        sb.append("VALIDATION SUMMARY\n");
        sb.append("Questions run: ").append(total).append('\n');
        sb.append("Passed: ").append(passed).append('/').append(total).append('\n');
        sb.append("Failures by class:\n");
        for (FailureClass fc : FailureClass.values()) {
            if (fc == FailureClass.NONE) continue;
            int n = failureCounts.getOrDefault(fc, 0);
            if (n > 0) {
                sb.append("  - ").append(fc.label).append(": ").append(n).append('\n');
            }
        }
        sb.append("\nFailed scenarios:\n");
        for (ValidationResult r : results) {
            if (r.failureClass() != FailureClass.NONE) {
                sb.append("  [").append(r.failureClass().label).append("] ")
                        .append(r.vc().dataset()).append(" — ")
                        .append(r.vc().question()).append('\n');
            }
        }
        return sb.toString();
    }

    enum FailureClass {
        NONE("none"),
        SEMANTIC_EXTRACTION("semantic extraction"),
        METRIC_RESOLUTION("metric resolution"),
        INTENT_DETECTION("intent detection"),
        DIMENSION_SELECTION("dimension selection"),
        RELATIONSHIP_SELECTION("relationship selection"),
        SQL_GENERATION("sql generation");

        final String label;

        FailureClass(String label) {
            this.label = label;
        }
    }

    record ValidationResult(ValidationQuestionBank.ValidationCase vc, FailureClass failureClass) {}
}
