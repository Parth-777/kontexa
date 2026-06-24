package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanFilterAugmenter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanScalarSqlBuilder;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanToAnalysisPlanAdapter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanValidationResult;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays benchmark artifacts and audits whether generated SQL faithfully represents planner intent.
 */
public final class SqlFidelityBenchmarkRunner {

    private SqlFidelityBenchmarkRunner() {}

    public record BenchmarkCase(
            int index,
            String question,
            StructuredSemanticPlan plan,
            SemanticPlanValidationResult validation,
            String tableRef,
            String sql,
            String queryKey,
            String source
    ) {}

    public record BenchmarkSummary(
            int totalCases,
            int sqlFidelityPassCount,
            int measureMatch,
            int aggregationMatch,
            int partitionMatch,
            int orderingMatch,
            int limitMatch,
            int relationshipMatch,
            int timeGrainMatch,
            Map<String, Map<String, Integer>> failureAttribution,
            List<ObjectNode> cases
    ) {}

    public static BenchmarkSummary run(
            List<BenchmarkCase> cases,
            ObjectMapper mapper
    ) {
        CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();
        List<ObjectNode> caseReports = new ArrayList<>();
        List<SqlFidelityAttributor.CaseAttribution> attributions = new ArrayList<>();

        int pass = 0;
        int measureOk = 0;
        int aggOk = 0;
        int partitionOk = 0;
        int orderingOk = 0;
        int limitOk = 0;
        int relationshipOk = 0;
        int timeGrainOk = 0;

        for (BenchmarkCase c : cases) {
            CanonicalQueryModel canonical = canonicalAdapter.adapt(c.plan());
            SqlFidelityReport.Result report = SqlFidelityReport.compare(canonical, c.sql());

            if (report.allMatch()) pass++;
            if (report.measureMatch()) measureOk++;
            if (report.aggregationMatch()) aggOk++;
            if (report.partitionMatch()) partitionOk++;
            if (report.orderingMatch()) orderingOk++;
            if (report.limitMatch()) limitOk++;
            if (report.relationshipMatch()) relationshipOk++;
            if (report.timeGrainMatch()) timeGrainOk++;

            if (!report.mutations().isEmpty()) {
                String intent = canonical.metadata() != null ? canonical.metadata().intent() : null;
                attributions.add(new SqlFidelityAttributor.CaseAttribution(
                        intent, c.queryKey(), report.mutations()));
            }

            ObjectNode caseNode = mapper.createObjectNode();
            caseNode.put("index", c.index());
            caseNode.put("question", c.question());
            caseNode.put("source", c.source());
            caseNode.put("queryKey", c.queryKey());
            caseNode.set("structuredSemanticPlan", mapper.valueToTree(c.plan()));
            caseNode.set("canonicalQueryModel", mapper.valueToTree(canonical));
            caseNode.put("sql", c.sql());
            caseNode.set("sqlFidelity", mapper.valueToTree(report));
            caseReports.add(caseNode);
        }

        return new BenchmarkSummary(
                cases.size(),
                pass,
                measureOk,
                aggOk,
                partitionOk,
                orderingOk,
                limitOk,
                relationshipOk,
                timeGrainOk,
                SqlFidelityAttributor.rankFailures(attributions),
                caseReports);
    }

    public static List<BenchmarkCase> loadCases(
            Path gapAnalysisPath,
            Path shadowFirst20Path,
            Path semanticFidelityLogPath,
            Path shadowLogPath,
            int limit,
            ApprovedCatalogueSnapshot catalogue,
            SemanticPlanToAnalysisPlanAdapter adapter,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle,
            ObjectMapper mapper
    ) throws Exception {
        Map<String, BenchmarkCase> byKey = new LinkedHashMap<>();
        int index = 1;

        if (Files.exists(gapAnalysisPath)) {
            JsonNode root = mapper.readTree(gapAnalysisPath.toFile());
            if (root.isArray()) {
                for (JsonNode entry : root) {
                    addCase(byKey, buildFromGapEntry(entry, mapper), index++);
                }
            }
        }

        if (Files.exists(shadowFirst20Path)) {
            JsonNode root = mapper.readTree(shadowFirst20Path.toFile());
            if (root.isArray()) {
                for (JsonNode entry : root) {
                    addCase(byKey, buildFromShadowJsonEntry(entry, mapper, "phase2-shadow-first20"), index++);
                }
            }
        }

        if (Files.exists(semanticFidelityLogPath)) {
            int lineNo = 1;
            for (String line : Files.readAllLines(semanticFidelityLogPath)) {
                if (line.isBlank()) continue;
                JsonNode entry = mapper.readTree(line);
                addCase(byKey, buildFromFidelityLogEntry(
                        entry, mapper, catalogue, adapter, sqlPlanner, bundle,
                        "semantic-fidelity.log#" + lineNo++), index++);
            }
        }

        if (Files.exists(shadowLogPath)) {
            int lineNo = 1;
            for (String line : Files.readAllLines(shadowLogPath)) {
                if (line.isBlank()) continue;
                JsonNode entry = mapper.readTree(line);
                addCase(byKey, buildFromShadowLogEntry(
                        entry, mapper, catalogue, adapter, sqlPlanner, bundle,
                        "phase2-shadow.log#" + lineNo++), index++);
            }
        }

        List<BenchmarkCase> all = new ArrayList<>(byKey.values());
        if (all.size() <= limit) {
            return reindex(all);
        }
        return reindex(all.subList(all.size() - limit, all.size()));
    }

    public static void writeReport(BenchmarkSummary summary, Path outputPath, ObjectMapper mapper)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCases", summary.totalCases());
        root.put("sqlFidelityPassCount", summary.sqlFidelityPassCount());
        root.put("sqlFidelityRate", rate(summary.sqlFidelityPassCount(), summary.totalCases()));

        ObjectNode fieldRates = root.putObject("fieldMatchRates");
        fieldRates.put("measureMatch", rate(summary.measureMatch(), summary.totalCases()));
        fieldRates.put("aggregationMatch", rate(summary.aggregationMatch(), summary.totalCases()));
        fieldRates.put("partitionMatch", rate(summary.partitionMatch(), summary.totalCases()));
        fieldRates.put("orderingMatch", rate(summary.orderingMatch(), summary.totalCases()));
        fieldRates.put("limitMatch", rate(summary.limitMatch(), summary.totalCases()));
        fieldRates.put("relationshipMatch", rate(summary.relationshipMatch(), summary.totalCases()));
        fieldRates.put("timeGrainMatch", rate(summary.timeGrainMatch(), summary.totalCases()));

        ObjectNode attribution = root.putObject("failureAttribution");
        summary.failureAttribution().forEach((field, suspects) -> {
            ArrayNode ranked = attribution.putArray(field);
            suspects.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        ObjectNode row = ranked.addObject();
                        row.put("suspect", e.getKey());
                        row.put("count", e.getValue());
                    });
        });

        ArrayNode cases = root.putArray("cases");
        summary.cases().forEach(cases::add);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
    }

    private static void addCase(Map<String, BenchmarkCase> byKey, BenchmarkCase candidate, int index) {
        if (candidate == null || candidate.sql() == null || candidate.sql().isBlank()) {
            return;
        }
        String key = candidate.source() + "|" + candidate.question();
        byKey.putIfAbsent(key, withIndex(candidate, index));
    }

    private static BenchmarkCase withIndex(BenchmarkCase c, int index) {
        return new BenchmarkCase(
                index, c.question(), c.plan(), c.validation(), c.tableRef(),
                c.sql(), c.queryKey(), c.source());
    }

    private static List<BenchmarkCase> reindex(List<BenchmarkCase> cases) {
        List<BenchmarkCase> out = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            BenchmarkCase c = cases.get(i);
            out.add(new BenchmarkCase(
                    i + 1, c.question(), c.plan(), c.validation(), c.tableRef(),
                    c.sql(), c.queryKey(), c.source()));
        }
        return out;
    }

    private static BenchmarkCase buildFromGapEntry(JsonNode entry, ObjectMapper mapper) throws Exception {
        StructuredSemanticPlan plan = mapper.treeToValue(
                entry.path("gptStructuredPlan"), StructuredSemanticPlan.class);
        boolean valid = entry.path("runtimeValidatorValid").asBoolean(false);
        List<String> issues = readIssues(entry.path("runtimeValidatorIssues"));
        SemanticPlanValidationResult validation = valid
                ? SemanticPlanValidationResult.ok()
                : SemanticPlanValidationResult.fail(issues);
        String tableRef = entry.path("analysisPlan").path("tableRef").asText("oil");
        SqlArtifact sqlArtifact = extractSqlArtifact(entry);
        return new BenchmarkCase(
                entry.path("index").asInt(),
                entry.path("question").asText(),
                plan,
                validation,
                tableRef,
                sqlArtifact.sql(),
                sqlArtifact.queryKey(),
                "phase2-gap-analysis.json");
    }

    private static BenchmarkCase buildFromShadowJsonEntry(
            JsonNode entry, ObjectMapper mapper, String source
    ) throws Exception {
        StructuredSemanticPlan plan = mapper.treeToValue(
                entry.path("gptStructuredPlan"), StructuredSemanticPlan.class);
        if (plan == null) {
            plan = mapper.treeToValue(entry.path("structuredSemanticPlan"), StructuredSemanticPlan.class);
        }
        boolean valid = entry.path("gptValidationValid").asBoolean(
                entry.path("runtimeValidatorValid").asBoolean(false));
        List<String> issues = readIssues(entry.path("gptValidationIssues"));
        if (issues.isEmpty()) {
            issues = readIssues(entry.path("runtimeValidatorIssues"));
        }
        SemanticPlanValidationResult validation = valid
                ? SemanticPlanValidationResult.ok()
                : SemanticPlanValidationResult.fail(issues);
        String tableRef = entry.path("legacyAnalysisPlan").path("tableRef")
                .asText(entry.path("analysisPlan").path("tableRef").asText("oil"));
        SqlArtifact sqlArtifact = extractSqlArtifact(entry);
        return new BenchmarkCase(
                entry.path("index").asInt(0),
                entry.path("question").asText(),
                plan,
                validation,
                tableRef,
                sqlArtifact.sql(),
                sqlArtifact.queryKey(),
                source);
    }

    private static BenchmarkCase buildFromFidelityLogEntry(
            JsonNode entry,
            ObjectMapper mapper,
            ApprovedCatalogueSnapshot catalogue,
            SemanticPlanToAnalysisPlanAdapter adapter,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle,
            String source
    ) throws Exception {
        StructuredSemanticPlan plan = mapper.treeToValue(
                entry.path("structuredSemanticPlan"), StructuredSemanticPlan.class);
        AnalysisPlan analysisPlan = mapper.treeToValue(entry.path("analysisPlan"), AnalysisPlan.class);
        String question = entry.path("question").asText(analysisPlan != null ? analysisPlan.question() : "");
        String tableRef = analysisPlan != null && analysisPlan.tableRef() != null
                ? analysisPlan.tableRef() : catalogue.tableRef();
        SemanticPlanValidationResult validation = SemanticPlanValidationResult.ok();
        QuerySpec spec = resolvePrimarySpec(plan, analysisPlan, catalogue, adapter, sqlPlanner, bundle);
        if (spec == null) {
            return null;
        }
        return new BenchmarkCase(
                0,
                question,
                plan,
                validation,
                tableRef,
                spec.sql(),
                spec.key(),
                source);
    }

    private static BenchmarkCase buildFromShadowLogEntry(
            JsonNode entry,
            ObjectMapper mapper,
            ApprovedCatalogueSnapshot catalogue,
            SemanticPlanToAnalysisPlanAdapter adapter,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle,
            String source
    ) throws Exception {
        StructuredSemanticPlan plan = mapper.treeToValue(
                entry.path("gptStructuredPlan"), StructuredSemanticPlan.class);
        boolean valid = entry.path("gptValidationValid").asBoolean(false);
        SemanticPlanValidationResult validation = valid
                ? SemanticPlanValidationResult.ok()
                : SemanticPlanValidationResult.fail(readIssues(entry.path("gptValidationIssues")));
        String tableRef = entry.path("legacyAnalysisPlan").path("tableRef").asText(catalogue.tableRef());
        SqlArtifact embedded = extractSqlArtifact(entry);
        if (embedded.sql() != null && !embedded.sql().isBlank()) {
            return new BenchmarkCase(
                    0,
                    entry.path("question").asText(),
                    plan,
                    validation,
                    tableRef,
                    embedded.sql(),
                    embedded.queryKey(),
                    source);
        }
        AnalysisPlan analysisPlan = adapter.toAnalysisPlan(
                entry.path("question").asText(), tableRef, plan, validation, catalogue);
        QuerySpec spec = resolvePrimarySpec(plan, analysisPlan, catalogue, adapter, sqlPlanner, bundle);
        if (spec == null) {
            return null;
        }
        return new BenchmarkCase(
                0,
                entry.path("question").asText(),
                plan,
                validation,
                tableRef,
                spec.sql(),
                spec.key(),
                source);
    }

    private static QuerySpec resolvePrimarySpec(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            ApprovedCatalogueSnapshot catalogue,
            SemanticPlanToAnalysisPlanAdapter adapter,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle
    ) {
        if (analysisPlan == null || !analysisPlan.executable()) {
            return null;
        }
        if (SemanticPlanScalarSqlBuilder.isScalarFiltered(plan)) {
            return SemanticPlanScalarSqlBuilder.build(catalogue.qualifiedTableName(), plan);
        }
        List<QuerySpec> specs = SemanticPlanFilterAugmenter.applyFilters(
                sqlPlanner.plan(analysisPlan, bundle), plan.filters());
        return specs.isEmpty() ? null : specs.get(0);
    }

    private static SqlArtifact extractSqlArtifact(JsonNode entry) {
        if (entry.path("loggedGptSql").isArray() && !entry.path("loggedGptSql").isEmpty()) {
            JsonNode first = entry.path("loggedGptSql").get(0);
            return new SqlArtifact(first.path("sql").asText(null), first.path("key").asText(null));
        }
        if (entry.path("gptSql").isArray() && !entry.path("gptSql").isEmpty()) {
            JsonNode first = entry.path("gptSql").get(0);
            return new SqlArtifact(first.path("sql").asText(null), first.path("key").asText(null));
        }
        if (entry.path("recomputedSql").isArray() && !entry.path("recomputedSql").isEmpty()) {
            return new SqlArtifact(entry.path("recomputedSql").get(0).asText(null), null);
        }
        return new SqlArtifact(null, null);
    }

    private static List<String> readIssues(JsonNode issuesNode) {
        List<String> issues = new ArrayList<>();
        if (issuesNode != null && issuesNode.isArray()) {
            issuesNode.forEach(n -> issues.add(n.asText()));
        }
        return issues;
    }

    private static double rate(int matched, int total) {
        if (total == 0) return 0.0;
        return 100.0 * matched / total;
    }

    private record SqlArtifact(String sql, String queryKey) {}
}
