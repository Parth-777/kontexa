package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.GroupedMetricSqlBuilder;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.IntentAggregationStrategy;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.RankingSqlTemplate;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityBenchmarkRunner;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces root-cause evidence for semantic drift between planner output and generated SQL.
 */
public final class SqlDriftRootCauseAnalyzer {

    private final CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();
    private final SemanticPlanToAnalysisPlanAdapter analysisAdapter;
    private final DeterministicAnalyticalQueryPlanner sqlPlanner;
    private final SqlDriftPipelineTracer tracer;

    public SqlDriftRootCauseAnalyzer(
            SemanticPlanToAnalysisPlanAdapter analysisAdapter,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            IntentAggregationStrategy aggregationStrategy,
            GroupedMetricSqlBuilder groupedMetricSqlBuilder,
            RankingSqlTemplate rankingSqlTemplate
    ) {
        this.analysisAdapter = analysisAdapter;
        this.sqlPlanner = sqlPlanner;
        this.tracer = new SqlDriftPipelineTracer(
                aggregationStrategy, groupedMetricSqlBuilder, rankingSqlTemplate);
    }

    public record MutationEvidence(
            String field,
            String expected,
            String actual,
            String firstMutationClass,
            String firstMutationMethod,
            int firstMutationLine,
            String firstMutationSourceFile,
            List<String> executionPath
    ) {}

    public record CaseEvidence(
            int index,
            String question,
            String source,
            String queryKey,
            StructuredSemanticPlan planner,
            CanonicalQueryModel canonicalQueryModel,
            AnalysisPlan analysisPlan,
            String generatedSql,
            boolean sqlFidelityPass,
            List<MutationEvidence> mutations
    ) {}

    public record RootCauseSummary(
            int totalCases,
            int failedCases,
            int sqlFidelityPassCount,
            Map<String, List<GroupedMutation>> groupedMutations,
            List<CaseEvidence> failedCaseEvidence
    ) {}

    public record GroupedMutation(
            String className,
            String methodName,
            int lineNumber,
            int count,
            List<String> affectedQuestions
    ) {}

    public RootCauseSummary analyze(
            List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases,
            ApprovedCatalogueSnapshot catalogue,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle
    ) {
        List<CaseEvidence> failed = new ArrayList<>();
        int passCount = 0;

        for (SqlFidelityBenchmarkRunner.BenchmarkCase c : cases) {
            CanonicalQueryModel canonical = canonicalAdapter.adapt(c.plan());
            AnalysisPlan analysisPlan = analysisAdapter.toAnalysisPlan(
                    c.question(), c.tableRef(), c.plan(), c.validation(), catalogue);

            QuerySpec spec = resolveSql(c, analysisPlan, catalogue, bundle);
            String sql = spec != null ? spec.sql() : c.sql();
            String queryKey = spec != null ? spec.key() : c.queryKey();

            SqlFidelityReport.Result fidelity = SqlFidelityReport.compare(canonical, sql);
            if (fidelity.allMatch()) {
                passCount++;
                continue;
            }

            List<SqlDriftPipelineTracer.FieldTrace> traces =
                    tracer.trace(c.plan(), analysisPlan, sql, fidelity);
            List<MutationEvidence> mutations = traces.stream()
                    .map(t -> toEvidence(t, analysisPlan))
                    .toList();

            failed.add(new CaseEvidence(
                    c.index(),
                    c.question(),
                    c.source(),
                    queryKey,
                    c.plan(),
                    canonical,
                    analysisPlan,
                    sql,
                    false,
                    mutations));
        }

        return new RootCauseSummary(
                cases.size(),
                failed.size(),
                passCount,
                groupMutations(failed),
                failed);
    }

    public static void writeJsonReport(RootCauseSummary summary, Path outputPath, ObjectMapper mapper)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCases", summary.totalCases());
        root.put("failedCaseCount", summary.failedCases());
        root.put("sqlFidelityPassCount", summary.sqlFidelityPassCount());
        root.put("sqlFidelityRate", rate(summary.sqlFidelityPassCount(), summary.totalCases()));

        ObjectNode grouped = root.putObject("groupedRootCauses");
        summary.groupedMutations().forEach((field, entries) -> {
            ArrayNode arr = grouped.putArray(field);
            for (GroupedMutation gm : entries) {
                ObjectNode node = arr.addObject();
                node.put("className", gm.className());
                node.put("methodName", gm.methodName());
                node.put("lineNumber", gm.lineNumber());
                node.put("count", gm.count());
                ArrayNode questions = node.putArray("affectedQuestions");
                gm.affectedQuestions().forEach(questions::add);
            }
        });

        ArrayNode cases = root.putArray("cases");
        for (CaseEvidence c : summary.failedCaseEvidence()) {
            ObjectNode caseNode = cases.addObject();
            caseNode.put("index", c.index());
            caseNode.put("question", c.question());
            caseNode.put("source", c.source());
            caseNode.put("queryKey", c.queryKey());
            caseNode.set("planner", mapper.valueToTree(c.planner()));
            caseNode.set("canonicalQueryModel", mapper.valueToTree(c.canonicalQueryModel()));
            caseNode.set("analysisPlan", mapper.valueToTree(c.analysisPlan()));
            caseNode.put("generatedSql", c.generatedSql());
            caseNode.put("sqlFidelityPass", c.sqlFidelityPass());

            ArrayNode mutations = caseNode.putArray("mutations");
            for (MutationEvidence m : c.mutations()) {
                ObjectNode mutation = mutations.addObject();
                mutation.put("field", m.field());
                mutation.put("expected", m.expected());
                mutation.put("actual", m.actual());
                mutation.put("firstMutationClass", m.firstMutationClass());
                mutation.put("firstMutationMethod", m.firstMutationMethod());
                mutation.put("firstMutationLine", m.firstMutationLine());
                mutation.put("firstMutationSourceFile", m.firstMutationSourceFile());
                ArrayNode path = mutation.putArray("executionPath");
                m.executionPath().forEach(path::add);
            }
        }

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
    }

    public static void writeMarkdownSummary(RootCauseSummary summary, Path outputPath) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# SQL Drift Root Cause Summary\n\n");
        md.append("Total benchmark cases: ").append(summary.totalCases()).append("\n\n");
        md.append("SQL fidelity pass: ")
                .append(summary.sqlFidelityPassCount())
                .append(" / ")
                .append(summary.totalCases())
                .append(" (")
                .append(String.format("%.0f", rate(summary.sqlFidelityPassCount(), summary.totalCases())))
                .append("%)\n\n");
        md.append("Failed cases: ").append(summary.failedCases()).append("\n\n");

        appendGroupedSection(md, "Aggregation mutations", "aggregation", summary);
        appendGroupedSection(md, "Relationship mutations", "relationshipOperands", summary);
        appendGroupedSection(md, "Ordering mutations", "ordering", summary);
        appendGroupedSection(md, "Limit mutations", "limit", summary);
        appendGroupedSection(md, "Partition mutations", "partition", summary);
        appendGroupedSection(md, "Measure mutations", "measure", summary);
        appendGroupedSection(md, "Time grain mutations", "timeGrain", summary);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        Files.writeString(outputPath, md);
    }

    private static void appendGroupedSection(
            StringBuilder md,
            String title,
            String fieldKey,
            RootCauseSummary summary
    ) {
        md.append("## ").append(title).append("\n\n");
        List<GroupedMutation> grouped = summary.groupedMutations().get(fieldKey);
        if (grouped == null || grouped.isEmpty()) {
            md.append("No mutations recorded.\n\n");
            return;
        }
        md.append("| Class | Method | Line | Count | Affected questions |\n");
        md.append("|-------|--------|------|-------|--------------------|\n");
        for (GroupedMutation gm : grouped) {
            md.append("| ").append(gm.className())
                    .append(" | ").append(gm.methodName())
                    .append(" | ").append(gm.lineNumber())
                    .append(" | ").append(gm.count())
                    .append(" | ");
            if (gm.affectedQuestions().size() <= 3) {
                md.append(String.join("; ", gm.affectedQuestions()));
            } else {
                md.append(gm.affectedQuestions().size()).append(" questions");
            }
            md.append(" |\n");
        }
        md.append("\n");
    }

    private QuerySpec resolveSql(
            SqlFidelityBenchmarkRunner.BenchmarkCase c,
            AnalysisPlan analysisPlan,
            ApprovedCatalogueSnapshot catalogue,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle
    ) {
        if (analysisPlan == null || !analysisPlan.executable()) {
            return null;
        }
        if (SemanticPlanScalarSqlBuilder.isScalarFiltered(c.plan())) {
            return SemanticPlanScalarSqlBuilder.build(catalogue.qualifiedTableName(), c.plan());
        }
        List<QuerySpec> specs = SemanticPlanFilterAugmenter.applyFilters(
                sqlPlanner.plan(analysisPlan, bundle), c.plan().filters());
        return specs.isEmpty() ? null : specs.get(0);
    }

    private MutationEvidence toEvidence(
            SqlDriftPipelineTracer.FieldTrace trace,
            AnalysisPlan analysisPlan
    ) {
        SqlDriftPipelineTracer.PipelineCheckpoint first = trace.firstMutation();
        List<String> path = trace.executionPath().stream()
                .map(SqlDriftPipelineTracer.PipelineCheckpoint::location)
                .toList();
        return new MutationEvidence(
                trace.field(),
                trace.expected(),
                trace.actual(),
                first.className(),
                first.methodName(),
                first.lineNumber(),
                first.sourceFile(),
                path);
    }

    private static Map<String, List<GroupedMutation>> groupMutations(List<CaseEvidence> failed) {
        Map<String, Map<String, GroupedMutationBuilder>> grouped = new LinkedHashMap<>();

        for (CaseEvidence c : failed) {
            for (MutationEvidence m : c.mutations()) {
                String key = m.firstMutationClass() + "#" + m.firstMutationMethod() + "#" + m.firstMutationLine();
                grouped.computeIfAbsent(m.field(), f -> new LinkedHashMap<>())
                        .computeIfAbsent(key, k -> new GroupedMutationBuilder(
                                m.firstMutationClass(),
                                m.firstMutationMethod(),
                                m.firstMutationLine()))
                        .add(c.question());
            }
        }

        Map<String, List<GroupedMutation>> result = new LinkedHashMap<>();
        grouped.forEach((field, builders) -> {
            List<GroupedMutation> list = builders.values().stream()
                    .map(GroupedMutationBuilder::build)
                    .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                    .toList();
            result.put(field, list);
        });
        return result;
    }

    private static double rate(int matched, int total) {
        if (total == 0) return 0.0;
        return 100.0 * matched / total;
    }

    private static final class GroupedMutationBuilder {
        private final String className;
        private final String methodName;
        private final int lineNumber;
        private final Set<String> questions = new LinkedHashSet<>();

        private GroupedMutationBuilder(String className, String methodName, int lineNumber) {
            this.className = className;
            this.methodName = methodName;
            this.lineNumber = lineNumber;
        }

        private void add(String question) {
            questions.add(question);
        }

        private GroupedMutation build() {
            return new GroupedMutation(
                    className, methodName, lineNumber, questions.size(), List.copyOf(questions));
        }
    }
}
