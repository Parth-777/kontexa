package com.example.BACKEND.catalogue.decision.regression;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfiler;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalQueryMaterializer;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalWarehouseResultDetector;
import com.example.BACKEND.catalogue.decision.execution.materialization.DerivedDimensionMaterializer;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupByExecutor;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupedWarehouseResultDetector;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationPlanBuilder;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.NumericDimensionBucketer;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DatasetProfileRegistry;
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
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import com.example.BACKEND.catalogue.decision.transforms.BigQueryDialect;
import com.example.BACKEND.catalogue.decision.transforms.BucketizationEngine;
import com.example.BACKEND.catalogue.decision.transforms.DerivedDimensionRegistry;
import com.example.BACKEND.catalogue.decision.transforms.SchemaColumnDetector;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationEngine;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationResult;
import com.example.BACKEND.catalogue.decision.transforms.TemporalDerivationEngine;
import com.example.BACKEND.catalogue.decision.transforms.TransformationStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Schema-registry-only regression: every pipeline stage must reference only columns
 * registered for the dataset under test (plus explicit transformation outputs).
 *
 * Output: stdout and {@code target/schema-only-client-regression.log}
 */
class SchemaOnlyClientDatasetRegressionTest {

    private static final Path LOG_FILE = Path.of("target", "schema-only-client-regression.log");
    private static PrintWriter log;
    private static final List<ContaminationReport> contaminationReports = new ArrayList<>();

    private QuestionSemanticExtractor extractor;
    private MetricResolutionEngine metricEngine;
    private QuestionInvestigationPlanner investigationPlanner;
    private UniversalAnalysisPlanner analysisPlanner;
    private DeterministicAnalyticalQueryPlanner sqlPlanner;
    private SemanticTransformationEngine transformationEngine;
    private AnalyticalQueryMaterializer materializer;
    private SchemaProfiler schemaProfiler;

    @BeforeAll
    static void openLog() throws IOException {
        Files.createDirectories(LOG_FILE.getParent());
        log = new PrintWriter(Files.newBufferedWriter(LOG_FILE));
        log.println("Schema-only client dataset regression — " + java.time.Instant.now());
        log.flush();
    }

    @BeforeEach
    void setUp() {
        extractor = MetricResolutionTestSupport.extractor();
        metricEngine = MetricResolutionTestSupport.engine();
        investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
        analysisPlanner = UniversalPlannerTestSupport.universalPlanner();
        SqlTemplateTestHarness harness = SqlTemplateTestHarness.create();
        sqlPlanner = harness.planner;
        transformationEngine = new SemanticTransformationEngine(
                new DerivedDimensionRegistry(),
                new SchemaColumnDetector(),
                new TemporalDerivationEngine(new BigQueryDialect()),
                new BucketizationEngine(),
                new DatasetProfileRegistry());
        materializer = new AnalyticalQueryMaterializer(
                new DerivedDimensionMaterializer(),
                new NumericDimensionBucketer(new MetricBucketingEngine()),
                new MaterializationPlanBuilder(new PresentationLabelResolver()),
                new GroupByExecutor(),
                new PresentationLabelResolver());
        schemaProfiler = new SchemaProfiler();
    }

    @org.junit.jupiter.api.AfterAll
    static void closeLog() {
        if (!contaminationReports.isEmpty()) {
            String summary = buildContaminationSummary();
            System.err.println(summary);
            if (log != null) {
                log.println(summary);
            }
        }
        if (log != null) {
            log.flush();
            log.close();
        }
    }

    static Stream<Arguments> scenarios() {
        List<Arguments> args = new ArrayList<>();
        for (ClientSchema schema : ClientSchema.all()) {
            for (QuestionCase question : schema.questions()) {
                args.add(Arguments.of(
                        schema.datasetName(),
                        question.label(),
                        question.expectedIntent().name(),
                        question.text(),
                        schema.bundle()));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("scenarios")
    void pipeline(
            String datasetName,
            String questionLabel,
            String expectedIntent,
            String question,
            RegistryResolutionBundle bundle
    ) {
        AnalysisIntent expected = AnalysisIntent.valueOf(expectedIntent);
        DatasetColumnAllowlist allowlist = DatasetColumnAllowlist.from(bundle);
        List<String> failures = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append("\n========== ").append(datasetName).append(" / ").append(questionLabel)
                .append(" / ").append(expectedIntent).append(" ==========\n");
        out.append("question: ").append(question).append('\n');
        out.append("allowed registry columns: ").append(allowlist.registryColumns()).append('\n');

        QuestionSemantics semantics = extractor.extract(question, bundle);
        auditColumn(allowlist, semantics.primaryMetric(), "QuestionSemantics.primaryMetric", failures);
        auditColumn(allowlist, semantics.targetMetric(), "QuestionSemantics.targetMetric", failures);
        auditColumn(allowlist, semantics.dimension(), "QuestionSemantics.dimension", failures);
        auditColumn(allowlist, semantics.grouping(), "QuestionSemantics.grouping", failures);
        if (semantics.extractedEntities() != null) {
            semantics.extractedEntities().forEach(c ->
                    auditColumnReference(allowlist, c, "QuestionSemantics.extractedEntities", failures));
        }
        if (semantics.temporalReferences() != null) {
            semantics.temporalReferences().forEach(ref ->
                    auditColumnReference(allowlist, ref, "QuestionSemantics.temporalReferences", failures));
        }
        logStage(out, "QuestionSemantics", semantics.primaryMetric(), semantics.dimension(), semantics.intent());

        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        auditColumn(allowlist, resolution.primaryMetric(), "MetricResolution.primaryMetric", failures);
        auditColumn(allowlist, resolution.targetMetric(), "MetricResolution.targetMetric", failures);
        auditColumn(allowlist, resolution.dimension(), "MetricResolution.dimension", failures);
        auditColumn(allowlist, resolution.grouping(), "MetricResolution.grouping", failures);
        auditColumn(allowlist, resolution.relationshipVariable(), "MetricResolution.relationshipVariable", failures);
        logStage(out, "MetricResolution", resolution.primaryMetric(), resolution.dimension(), semantics.intent());

        if (resolution.rejected()) {
            failures.add("RESOLUTION: " + resolution.rejectionReason());
        } else if (resolution.primaryMetric() == null || resolution.primaryMetric().isBlank()) {
            failures.add("RESOLUTION: primary metric unresolved");
        }

        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        if (investigation.extraction() != null) {
            auditColumn(allowlist, investigation.extraction().metricKey(),
                    "QuestionInvestigation.extraction.metricKey", failures);
            auditColumn(allowlist, investigation.extraction().targetMetricKey(),
                    "QuestionInvestigation.extraction.targetMetricKey", failures);
        }
        if (investigation.dimension() != null && investigation.dimension().resolved()) {
            auditColumn(allowlist, investigation.dimension().columnKey(),
                    "QuestionInvestigation.dimension.columnKey", failures);
            auditColumn(allowlist, investigation.dimension().groupingAlias(),
                    "QuestionInvestigation.dimension.groupingAlias", failures);
        }
        logStage(out, "QuestionInvestigation",
                investigation.extraction() != null ? investigation.extraction().metricKey() : null,
                investigation.dimension() != null && investigation.dimension().resolved()
                        ? investigation.dimension().columnKey() : null,
                investigation.extraction() != null ? investigation.extraction().intent() : null);

        AnalysisPlan plan = analysisPlanner.plan(question, bundle, investigation, resolution, List.of());
        auditColumn(allowlist, plan.primaryMetric(), "AnalysisPlan.primaryMetric", failures);
        auditColumn(allowlist, plan.secondaryMetric(), "AnalysisPlan.secondaryMetric", failures);
        auditColumn(allowlist, plan.dimension(), "AnalysisPlan.dimension", failures);
        auditColumn(allowlist, plan.groupingAlias(), "AnalysisPlan.groupingAlias", failures);
        auditColumn(allowlist, plan.relationshipVariable(), "AnalysisPlan.relationshipVariable", failures);
        permitPlanDerivedAliases(allowlist, plan);
        permitTransformationOutputs(allowlist, question, plan, bundle);

        out.append("AnalysisPlan: intent=").append(plan.intent())
                .append(" executable=").append(plan.executable())
                .append(" table=").append(plan.tableRef())
                .append(" metric=").append(plan.primaryMetric())
                .append(" dimension=").append(plan.dimension())
                .append(" grouping=").append(plan.groupingAlias())
                .append(" relationship=").append(plan.relationshipVariable());
        if (!plan.executable()) {
            out.append(" blockers=").append(plan.blockingReason());
        }
        out.append('\n');

        if (!plan.executable()) {
            failures.add("PLANNING: " + plan.blockingReason());
        } else if (plan.intent() != expected) {
            failures.add("PLANNING: expected intent " + expected + " but got " + plan.intent());
        }

        String sql = "";
        if (failures.stream().noneMatch(f -> f.startsWith("PLANNING") || f.startsWith("RESOLUTION"))) {
            try {
                List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
                sql = specs.stream().map(QuerySpec::sql).reduce("", String::concat);
                out.append("generated SQL:\n").append(sql).append('\n');
                if (specs.isEmpty() || sql.isBlank()) {
                    failures.add("SQL_GENERATION: empty SQL");
                } else {
                    auditSql(allowlist, sql, plan.tableRef(), failures);
                }
            } catch (Exception ex) {
                failures.add("SQL_GENERATION: " + ex.getMessage());
                out.append("SQL error: ").append(ex.getMessage()).append('\n');
            }
        }

        if (failures.isEmpty()) {
            List<Map<String, Object>> rows = syntheticRows(plan, allowlist);
            out.append("warehouse result shape: ").append(describeWarehouseShape(rows)).append('\n');
            out.append("synthetic row sample keys: ").append(
                    rows.isEmpty() ? "[]" : rows.getFirst().keySet()).append('\n');
            rows.stream().flatMap(r -> r.keySet().stream()).distinct()
                    .forEach(k -> auditColumn(allowlist, k, "warehouseRow.keys", failures));

            var profile = schemaProfiler.profile(rows);
            MaterializedQueryResult materialized = materializer.materialize(rows, profile, null);
            out.append("materialized type: ").append(materialized.resultType())
                    .append(" hasContent=").append(materialized.hasContent()).append('\n');
            if (!materialized.hasContent()) {
                failures.add("MATERIALIZATION: no content from " + rows.size() + " rows");
            }
        }

        out.append("effective allowlist: ").append(allowlist.effectiveColumns()).append('\n');

        String block = out.toString();
        System.out.print(block);
        if (log != null) {
            log.print(block);
            log.flush();
        }

        if (!failures.isEmpty()) {
            StringBuilder report = new StringBuilder();
            report.append("FAILURES for ").append(datasetName).append(" / ").append(questionLabel)
                    .append(" — ").append(question).append('\n');
            for (String f : failures) {
                report.append("  - ").append(f).append('\n');
                if (f.startsWith("CONTAMINATION")) {
                    String path = ContaminationTracer.trace(question, f);
                    if (path != null) {
                        report.append("    path: ").append(path).append('\n');
                    }
                }
            }
            contaminationReports.add(new ContaminationReport(
                    datasetName, questionLabel, question, List.copyOf(failures)));
            System.err.print(report);
            if (log != null) {
                log.print(report);
                log.flush();
            }
            fail(report.toString());
        }
    }

    private void permitTransformationOutputs(
            DatasetColumnAllowlist allowlist,
            String question,
            AnalysisPlan plan,
            RegistryResolutionBundle bundle
    ) {
        if (!plan.executable() || plan.tableRef() == null) return;
        String dimension = plan.dimension();
        if (dimension == null || dimension.isBlank()) return;
        SemanticTransformationResult primary = transformationEngine.transform(
                question, plan.tableRef(), plan.primaryMetric(), dimension,
                plan.groupingAlias(), plan.intent().sqlKind(), "audit-primary", bundle);
        permitTransformResult(allowlist, primary);
        if (primary.traceSteps() != null) {
            plan.transformations().forEach(s -> permitTransformStep(allowlist, s));
        }
    }

    private void permitTransformResult(DatasetColumnAllowlist allowlist, SemanticTransformationResult result) {
        if (result == null || !result.success() || result.traceSteps() == null) return;
        result.traceSteps().forEach(s -> permitTransformStep(allowlist, s));
        if (result.dimension() != null) {
            allowlist.permitDerived(result.dimension().outputAlias());
            allowlist.permitDerived(result.dimension().sourceColumn());
        }
        if (result.templateContext() != null) {
            allowlist.permitDerived(result.templateContext().bucketAlias());
        }
    }

    private void permitTransformStep(DatasetColumnAllowlist allowlist, TransformationStep step) {
        if (step == null) return;
        allowlist.permitDerived(step.outputAlias());
        allowlist.permitDerived(step.sourceColumn());
        if (step.description() != null && step.description().startsWith("Column: ")) {
            allowlist.permitDerived(step.description().substring("Column: ".length()).trim());
        }
    }

    private void permitPlanDerivedAliases(DatasetColumnAllowlist allowlist, AnalysisPlan plan) {
        allowlist.permitDerived(plan.groupingAlias());
        allowlist.permitDerived(plan.dimension());
        if (plan.transformations() != null) {
            plan.transformations().forEach(s -> permitTransformStep(allowlist, s));
        }
    }

    private static String buildContaminationSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== CONTAMINATION SUMMARY (")
                .append(contaminationReports.size()).append(" scenarios) ==========\n");
        for (ContaminationReport r : contaminationReports) {
            sb.append(r.dataset()).append(" / ").append(r.label()).append('\n');
            sb.append("  question: ").append(r.question()).append('\n');
            for (String f : r.failures()) {
                sb.append("  - ").append(f).append('\n');
                if (f.startsWith("CONTAMINATION")) {
                    String path = ContaminationTracer.trace(r.question(), f);
                    if (path != null) {
                        sb.append("    path: ").append(path).append('\n');
                    }
                }
            }
        }
        sb.append("Invariant: every stage must reference only registry columns (+ transformations).\n");
        int total = ClientSchema.all().stream().mapToInt(s -> s.questions().size()).sum();
        sb.append(total - contaminationReports.size()).append('/').append(total)
                .append(" scenarios passed; ").append(contaminationReports.size())
                .append(" contaminated.\n");
        return sb.toString();
    }

    private record ContaminationReport(
            String dataset, String label, String question, List<String> failures) {}

    /**
     * Traces likely contamination source without hardcoding dataset columns.
     * Uses {@link com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary} entries.
     */
    static final class ContaminationTracer {
        private ContaminationTracer() {}

        static String trace(String question, String failureLine) {
            String column = extractColumn(failureLine);
            if (column == null) return null;
            String stage = extractStage(failureLine);
            return traceViaDictionary(question, column, stage);
        }

        private static String traceViaDictionary(String question, String column, String stage) {
            var dict = new com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary();
            String q = " " + question.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ") + " ";
            List<String> matchedPhrases = new ArrayList<>();
            for (var e : dict.entries()) {
                if (!column.equalsIgnoreCase(e.columnKey())) continue;
                String phrase = e.phrase().toLowerCase(Locale.ROOT);
                boolean hit = phrase.contains(" ")
                        ? q.contains(" " + phrase + " ")
                        : Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b").matcher(q).find();
                if (hit) matchedPhrases.add("\"" + e.phrase() + "\"->" + e.columnKey());
            }
            if (matchedPhrases.isEmpty()) {
                return "Question -> " + stage + " -> foreign column \"" + column
                        + "\" (not in RegistryResolutionBundle)";
            }
            return "Question -> QuestionSemanticExtractor.matchWithWordBoundaries"
                    + " -> SemanticDictionary[" + String.join(", ", matchedPhrases) + "]"
                    + " -> " + stage;
        }

        private static String extractColumn(String failureLine) {
            int idx = failureLine.indexOf("column=");
            if (idx < 0) return null;
            int end = failureLine.indexOf(' ', idx + 7);
            if (end < 0) end = failureLine.length();
            return failureLine.substring(idx + 7, end);
        }

        private static String extractStage(String failureLine) {
            int start = failureLine.indexOf('[');
            int end = failureLine.indexOf(']');
            if (start < 0 || end < 0) return "unknown";
            return failureLine.substring(start + 1, end);
        }
    }

    private static void auditColumn(
            DatasetColumnAllowlist allowlist, String column, String stage, List<String> failures
    ) {
        if (column == null || column.isBlank()) return;
        if (!allowlist.isPermitted(column)) {
            failures.add(contaminationMessage(stage, column, allowlist));
        }
    }

    /** Audits only values that look like schema column identifiers, not NL temporal hints. */
    private static void auditColumnReference(
            DatasetColumnAllowlist allowlist, String value, String stage, List<String> failures
    ) {
        if (value == null || value.isBlank()) return;
        if (!looksLikeColumnReference(value)) return;
        auditColumn(allowlist, value, stage, failures);
    }

    private static String contaminationMessage(
            String stage, String column, DatasetColumnAllowlist allowlist
    ) {
        return "CONTAMINATION [" + stage + "] column=" + column
                + " not in dataset registry or permitted transformations; registry="
                + allowlist.registryColumns();
    }

    private static final Set<String> NATURAL_LANGUAGE_TEMPORAL_HINTS = Set.of(
            "hour", "day", "week", "month", "year", "quarter", "minute", "second"
    );

    private static boolean looksLikeColumnReference(String value) {
        String norm = value.trim().toLowerCase(Locale.ROOT);
        if (norm.contains("_")) return true;
        return !NATURAL_LANGUAGE_TEMPORAL_HINTS.contains(norm);
    }

    private static void auditSql(
            DatasetColumnAllowlist allowlist, String sql, String tableRef, List<String> failures
    ) {
        for (String token : SqlColumnScanner.scan(sql)) {
            if (token.equalsIgnoreCase(tableRef)) continue;
            if (!allowlist.isPermitted(token)) {
                failures.add("CONTAMINATION [SQL] identifier=" + token
                        + " not in dataset registry or permitted transformations; registry="
                        + allowlist.registryColumns());
            }
        }
    }

    private static void logStage(
            StringBuilder out, String stage, Object metric, Object dimension, Object intent
    ) {
        out.append(stage).append(": metric=").append(metric)
                .append(" dimension=").append(dimension)
                .append(" intent=").append(intent).append('\n');
    }

  // ─── Allowlist ───────────────────────────────────────────────────────────

    static final class DatasetColumnAllowlist {
        private final Set<String> registry = new LinkedHashSet<>();
        private final Set<String> permitted = new LinkedHashSet<>();

        static DatasetColumnAllowlist from(RegistryResolutionBundle bundle) {
            DatasetColumnAllowlist a = new DatasetColumnAllowlist();
            if (bundle == null) return a;
            if (bundle.entities() != null) {
                for (EntityDescriptor e : bundle.entities()) {
                    a.permitRegistry(e.tableRef());
                    if (e.grainKeys() != null) {
                        e.grainKeys().forEach(a::permitRegistry);
                    }
                }
            }
            if (bundle.metrics() != null) {
                for (MetricDescriptor m : bundle.metrics()) {
                    a.permitRegistry(SemanticCatalogBuilder.bareColumn(m.key()));
                }
            }
            if (bundle.dimensions() != null) {
                for (DimensionDescriptor d : bundle.dimensions()) {
                    a.permitRegistry(SemanticCatalogBuilder.bareColumn(d.key()));
                }
            }
            SqlColumnScanner.COMPUTED_OUTPUTS.forEach(a.permitted::add);
            SqlColumnScanner.LOGICAL_GROUPINGS.forEach(a.permitted::add);
            return a;
        }

        Set<String> registryColumns() {
            return Set.copyOf(registry);
        }

        Set<String> effectiveColumns() {
            return Set.copyOf(permitted);
        }

        void permitRegistry(String column) {
            if (column == null || column.isBlank()) return;
            String norm = normalize(column);
            registry.add(norm);
            permitted.add(norm);
        }

        void permitDerived(String column) {
            if (column == null || column.isBlank()) return;
            String norm = normalize(column);
            permitted.add(norm);
            if (norm.endsWith("_bucket")) {
                permitted.add(norm.substring(0, norm.length() - "_bucket".length()));
            }
        }

        boolean isPermitted(String column) {
            if (column == null || column.isBlank()) return true;
            String norm = normalize(column);
            if (permitted.contains(norm)) return true;
            if (norm.endsWith("_bucket")) {
                String base = norm.substring(0, norm.length() - "_bucket".length());
                if (permitted.contains(base)) return true;
            }
            return false;
        }

        private static String normalize(String column) {
            return column.trim().toLowerCase(Locale.ROOT);
        }
    }

    static final class SqlColumnScanner {
        private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");

        static final Set<String> COMPUTED_OUTPUTS = Set.of(
                "share_pct", "correlation_coefficient", "row_count", "sample_size",
                "pearson_r", "correlation", "corr", "time_period", "entity", "segment_bucket"
        );

        static final Set<String> LOGICAL_GROUPINGS = Set.of(
                "composition", "relationship", "segment"
        );

        private static final Set<String> SQL_KEYWORDS = Set.of(
                "select", "from", "where", "group", "by", "order", "desc", "asc", "limit",
                "sum", "avg", "count", "round", "nullif", "over", "as", "and", "or", "not",
                "is", "null", "case", "when", "then", "else", "end", "extract", "hour",
                "day", "dayofweek", "safe_divide", "true", "false", "between", "in", "like",
                "distinct", "having", "join", "on", "coalesce", "cast", "inner", "left",
                "right", "outer", "with", "union", "all", "into", "values", "primary", "key"
        );

        static Set<String> scan(String sql) {
            Set<String> found = new LinkedHashSet<>();
            if (sql == null || sql.isBlank()) return found;
            Matcher m = IDENTIFIER.matcher(sql);
            while (m.find()) {
                String token = m.group(1).toLowerCase(Locale.ROOT);
                if (SQL_KEYWORDS.contains(token)) continue;
                if (COMPUTED_OUTPUTS.contains(token)) continue;
                if (LOGICAL_GROUPINGS.contains(token)) continue;
                if (token.matches("\\d+")) continue;
                found.add(token);
            }
            return found;
        }
    }

    // ─── Warehouse helpers ───────────────────────────────────────────────────

    private static String describeWarehouseShape(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "EMPTY";
        var corr = AnalyticalWarehouseResultDetector.detectCorrelation(rows);
        if (corr.isPresent()) {
            return "CORRELATION coeff=" + corr.get().coefficient() + " n=" + corr.get().sampleSize();
        }
        var scalar = AnalyticalWarehouseResultDetector.detectScalar(rows);
        if (scalar.isPresent()) {
            return "SCALAR " + scalar.get().metricColumn() + "=" + scalar.get().value();
        }
        GroupedWarehouseResultDetector.GroupedShape grouped = GroupedWarehouseResultDetector.detect(rows);
        if (grouped != null) {
            return "GROUPED dim=" + grouped.dimensionColumn() + " metric=" + grouped.metricColumn()
                    + " rows=" + rows.size();
        }
        return "UNRECOGNIZED keys=" + rows.getFirst().keySet() + " rows=" + rows.size();
    }

    private static List<Map<String, Object>> syntheticRows(
            AnalysisPlan plan, DatasetColumnAllowlist allowlist
    ) {
        if (plan.intent() == AnalysisIntent.RELATIONSHIP) {
            return List.of(Map.of(
                    "correlation_coefficient", 0.42,
                    "row_count", 5000L));
        }
        String dim = plan.dimension() != null ? plan.dimension()
                : (plan.groupingAlias() != null ? plan.groupingAlias() : "segment");
        String metric = plan.primaryMetric() != null ? plan.primaryMetric() : "metric_value";
        allowlist.permitDerived(dim);
        allowlist.permitDerived(metric);

        if ("composition".equalsIgnoreCase(dim)) {
            return List.of(
                    Map.of("segment", "component_a", metric, 60.0),
                    Map.of("segment", "component_b", metric, 40.0));
        }
        return List.of(
                Map.of(dim, "alpha", metric, 100.0),
                Map.of(dim, "beta", metric, 80.0),
                Map.of(dim, "gamma", metric, 55.0));
    }

    // ─── Datasets & questions ────────────────────────────────────────────────

    private enum ClientSchema {
        FACILITY_OPS(schema(
                "facility_operations",
                "facility_operations",
                List.of("unit_cost", "output_volume", "defect_count"),
                List.of("line_code", "shift_label", "report_week"),
                questions(
                        ranking("Which line_code has the highest unit_cost?",
                                "Which production line has the highest unit cost?"),
                        contribution("How does each line_code contribute to unit_cost?",
                                "How does each production line contribute to unit cost?"),
                        relationship("How does output_volume affect unit_cost?",
                                "How does output volume affect unit cost?"),
                        distribution("Distribution of unit_cost by shift_label",
                                "Spread of unit cost across shift labels"),
                        trend("Unit_cost trend over report_week",
                                "Unit cost trend over reporting week"),
                        comparison("Compare unit_cost by line_code",
                                "Compare unit cost across production lines")
                ))),
        SUBSCRIPTION_EVENTS(schema(
                "subscription_events",
                "subscription_events",
                List.of("payment_total", "active_minutes", "cancellation_count"),
                List.of("plan_tier", "billing_region", "event_hour"),
                questions(
                        ranking("Which plan_tier has the highest payment_total?",
                                "Which subscription tier generates the most payment total?"),
                        contribution("How does each plan_tier contribute to payment_total?",
                                "How does each subscription tier contribute to payment total?"),
                        relationship("How does active_minutes affect payment_total?",
                                "How do active minutes affect payment total?"),
                        distribution("Distribution of payment_total by billing_region",
                                "Payment total distribution by billing region"),
                        trend("Payment_total trend over event_hour",
                                "Payment total trend over event hour"),
                        comparison("Compare payment_total by plan_tier",
                                "Compare payment total across subscription tiers")
                ))),
        WEATHER_OBS(schema(
                "weather_observations",
                "weather_observations",
                List.of("rainfall_total", "wind_speed", "pressure_value"),
                List.of("station_id", "climate_zone", "observation_month"),
                questions(
                        ranking("Which station_id has the highest rainfall_total?",
                                "Which weather station records the highest rainfall total?"),
                        contribution("How does each climate_zone contribute to rainfall_total?",
                                "How does each climate zone contribute to rainfall total?"),
                        relationship("How does wind_speed affect rainfall_total?",
                                "How does wind speed affect rainfall total?"),
                        distribution("Distribution of rainfall_total by climate_zone",
                                "Rainfall total distribution by climate zone"),
                        trend("Rainfall_total trend over observation_month",
                                "Rainfall total trend over observation month"),
                        comparison("Compare rainfall_total by station_id",
                                "Compare rainfall total across weather stations")
                ))),
        SEMICONDUCTOR_YIELD(schema(
                "semiconductor_yield",
                "semiconductor_yield",
                List.of("wafer_defect_rate", "batch_throughput", "lithography_yield"),
                List.of("fab_line", "process_node", "production_shift"),
                questions(
                        ranking("Which fab_line has the highest lithography_yield?",
                                "Which fabrication line achieves the highest lithography yield?"),
                        contribution("How does each process_node contribute to batch_throughput?",
                                "How does each process node contribute to batch throughput?"),
                        relationship("How does wafer_defect_rate affect lithography_yield?",
                                "How does wafer defect rate affect lithography yield?"),
                        distribution("Distribution of batch_throughput by production_shift",
                                "Batch throughput distribution by production shift"),
                        trend("Batch_throughput trend over production_shift",
                                "Batch throughput trend across production shift"),
                        comparison("Compare lithography_yield by fab_line",
                                "Compare lithography yield across fabrication lines")
                ))),
        HOSPITAL_BED_FLOW(schema(
                "hospital_bed_flow",
                "hospital_bed_flow",
                List.of("length_of_stay_hours", "readmission_count", "treatment_cost_total"),
                List.of("care_unit", "acuity_level", "admission_week"),
                questions(
                        ranking("Which care_unit has the highest treatment_cost_total?",
                                "Which care unit has the highest treatment cost total?"),
                        contribution("How does each acuity_level contribute to readmission_count?",
                                "How does each acuity level contribute to readmission count?"),
                        relationship("How does length_of_stay_hours affect treatment_cost_total?",
                                "How does length of stay affect treatment cost total?"),
                        distribution("Distribution of treatment_cost_total by care_unit",
                                "Treatment cost total distribution by care unit"),
                        trend("Treatment_cost_total trend over admission_week",
                                "Treatment cost total trend over admission week"),
                        comparison("Compare readmission_count by acuity_level",
                                "Compare readmission count across acuity levels")
                ))),
        ESPORTS_MATCHES(schema(
                "esports_matches",
                "esports_matches",
                List.of("match_duration_min", "viewer_peak", "prize_payout"),
                List.of("game_title", "team_region", "match_hour"),
                questions(
                        ranking("Which game_title has the highest viewer_peak?",
                                "Which game title attracts the highest viewer peak?"),
                        contribution("How does each team_region contribute to prize_payout?",
                                "How does each team region contribute to prize payout?"),
                        relationship("How does match_duration_min affect viewer_peak?",
                                "How does match duration affect viewer peak?"),
                        distribution("Distribution of prize_payout by team_region",
                                "Prize payout distribution by team region"),
                        trend("Viewer_peak trend over match_hour",
                                "Viewer peak trend over match hour"),
                        comparison("Compare prize_payout by game_title",
                                "Compare prize payout across game titles")
                ))),
        SATELLITE_TELEMETRY(schema(
                "satellite_telemetry",
                "satellite_telemetry",
                List.of("signal_strength_db", "orbit_deviation_km", "power_draw_watts"),
                List.of("spacecraft_id", "ground_station", "telemetry_month"),
                questions(
                        ranking("Which spacecraft_id has the highest signal_strength_db?",
                                "Which spacecraft reports the highest signal strength?"),
                        contribution("How does each ground_station contribute to power_draw_watts?",
                                "How does each ground station contribute to power draw?"),
                        relationship("How does orbit_deviation_km affect signal_strength_db?",
                                "How does orbit deviation affect signal strength?"),
                        distribution("Distribution of power_draw_watts by ground_station",
                                "Power draw distribution by ground station"),
                        trend("Signal_strength_db trend over telemetry_month",
                                "Signal strength trend over telemetry month"),
                        comparison("Compare orbit_deviation_km by spacecraft_id",
                                "Compare orbit deviation across spacecraft")
                ))),
        VINEYARD_PRODUCTION(schema(
                "vineyard_production",
                "vineyard_production",
                List.of("harvest_kilograms", "sugar_brix_level", "fermentation_volume"),
                List.of("vineyard_block", "grape_variety", "harvest_week"),
                questions(
                        ranking("Which vineyard_block has the highest harvest_kilograms?",
                                "Which vineyard block produces the most harvest kilograms?"),
                        contribution("How does each grape_variety contribute to fermentation_volume?",
                                "How does each grape variety contribute to fermentation volume?"),
                        relationship("How does sugar_brix_level affect fermentation_volume?",
                                "How does sugar brix level affect fermentation volume?"),
                        distribution("Distribution of harvest_kilograms by grape_variety",
                                "Harvest kilograms distribution by grape variety"),
                        trend("Fermentation_volume trend over harvest_week",
                                "Fermentation volume trend over harvest week"),
                        comparison("Compare sugar_brix_level by vineyard_block",
                                "Compare sugar brix level across vineyard blocks")
                )));

        private final String datasetName;
        private final RegistryResolutionBundle bundle;
        private final List<QuestionCase> questions;

        ClientSchema(SchemaDef def) {
            this.datasetName = def.datasetName();
            this.bundle = def.bundle();
            this.questions = def.questions();
        }

        static List<ClientSchema> all() {
            return List.of(values());
        }

        String datasetName() {
            return datasetName;
        }

        RegistryResolutionBundle bundle() {
            return bundle;
        }

        List<QuestionCase> questions() {
            return questions;
        }

        private static SchemaDef schema(
                String datasetName, String table,
                List<String> metrics, List<String> dimensions,
                List<QuestionCase> questions
        ) {
            return new SchemaDef(datasetName, registry(table, metrics, dimensions), questions);
        }

        private static RegistryResolutionBundle registry(
                String table, List<String> metrics, List<String> dimensions
        ) {
            return new RegistryResolutionBundle(
                    List.of(new EntityDescriptor(table, table, List.of("id"), List.of("client_" + table))),
                    metrics.stream()
                            .map(m -> new MetricDescriptor(table + "." + m, m, "FLOAT", "SUM", null))
                            .toList(),
                    dimensions.stream()
                            .map(d -> new DimensionDescriptor(
                                    table + "." + d, d, isTemporalName(d) ? "TEMPORAL" : "CATEGORICAL"))
                            .toList(),
                    new ObjectiveDescriptor("GENERAL", "ANALYTICAL", List.of()));
        }

        private static boolean isTemporalName(String d) {
            String lower = d.toLowerCase(Locale.ROOT);
            return lower.contains("week") || lower.contains("hour") || lower.contains("month")
                    || lower.contains("shift");
        }

        private static List<QuestionCase> questions(List<QuestionCase>... groups) {
            List<QuestionCase> all = new ArrayList<>();
            for (List<QuestionCase> g : groups) {
                all.addAll(g);
            }
            return all;
        }

        private static List<QuestionCase> ranking(String literal, String paraphrase) {
            return pair(AnalysisIntent.RANKING, "ranking", literal, paraphrase);
        }

        private static List<QuestionCase> contribution(String literal, String paraphrase) {
            return pair(AnalysisIntent.CONTRIBUTION, "contribution", literal, paraphrase);
        }

        private static List<QuestionCase> relationship(String literal, String paraphrase) {
            return pair(AnalysisIntent.RELATIONSHIP, "relationship", literal, paraphrase);
        }

        private static List<QuestionCase> distribution(String literal, String paraphrase) {
            return pair(AnalysisIntent.DISTRIBUTION, "distribution", literal, paraphrase);
        }

        private static List<QuestionCase> trend(String literal, String paraphrase) {
            return pair(AnalysisIntent.TREND, "trend", literal, paraphrase);
        }

        private static List<QuestionCase> comparison(String literal, String paraphrase) {
            return pair(AnalysisIntent.COMPARISON, "comparison", literal, paraphrase);
        }

        private static List<QuestionCase> pair(
                AnalysisIntent intent, String label, String literal, String paraphrase
        ) {
            return List.of(
                    new QuestionCase(intent, label + "-literal", literal),
                    new QuestionCase(intent, label + "-paraphrase", paraphrase));
        }
    }

    private record SchemaDef(
            String datasetName, RegistryResolutionBundle bundle, List<QuestionCase> questions) {}

    private record QuestionCase(AnalysisIntent expectedIntent, String label, String text) {}
}
