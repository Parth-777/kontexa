package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanToAnalysisPlanAdapter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanValidationResult;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanFilterAugmenter;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanScalarSqlBuilder;
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
 * Replays benchmark GPT plans and produces a field-preservation report without changing production behavior.
 */
public final class SemanticFidelityBenchmarkRunner {

    private static final int DEFAULT_LIMIT = 50;

    private SemanticFidelityBenchmarkRunner() {}

    public record BenchmarkCase(
            int index,
            String question,
            StructuredSemanticPlan plan,
            SemanticPlanValidationResult validation,
            String tableRef
    ) {}

    public record BenchmarkSummary(
            int totalCases,
            int measurePreserved,
            int aggregationPreserved,
            int partitionPreserved,
            int orderingPreserved,
            int limitPreserved,
            int relationshipOperandsPreserved,
            int timeGrainPreserved,
            int allPreserved,
            List<ObjectNode> cases
    ) {}

    public static BenchmarkSummary run(
            List<BenchmarkCase> cases,
            ApprovedCatalogueSnapshot catalogue,
            SemanticPlanToAnalysisPlanAdapter adapter,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle,
            ObjectMapper mapper
    ) throws Exception {
        CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();
        List<ObjectNode> caseReports = new ArrayList<>();

        int measureOk = 0;
        int aggOk = 0;
        int partitionOk = 0;
        int orderingOk = 0;
        int limitOk = 0;
        int relationshipOk = 0;
        int timeGrainOk = 0;
        int allOk = 0;

        for (BenchmarkCase c : cases) {
            CanonicalQueryModel canonical = canonicalAdapter.adapt(c.plan());
            AnalysisPlan analysisPlan = adapter.toAnalysisPlan(
                    c.question(), c.tableRef(), c.plan(), c.validation(), catalogue);
            List<QuerySpec> specs = resolveQuerySpecs(c.plan(), analysisPlan, catalogue, bundle, sqlPlanner);

            SemanticFidelityReport.Result fidelity = SemanticFidelityReport.compare(
                    canonical, analysisPlan, specs);

            if (fidelity.measurePreserved()) measureOk++;
            if (fidelity.aggregationPreserved()) aggOk++;
            if (fidelity.partitionPreserved()) partitionOk++;
            if (fidelity.orderingPreserved()) orderingOk++;
            if (fidelity.limitPreserved()) limitOk++;
            if (fidelity.relationshipOperandsPreserved()) relationshipOk++;
            if (fidelity.timeGrainPreserved()) timeGrainOk++;
            if (fidelity.allPreserved()) allOk++;

            ObjectNode caseNode = mapper.createObjectNode();
            caseNode.put("index", c.index());
            caseNode.put("question", c.question());
            caseNode.set("structuredSemanticPlan", mapper.valueToTree(c.plan()));
            caseNode.set("canonicalQueryModel", mapper.valueToTree(canonical));
            caseNode.set("analysisPlan", mapper.valueToTree(analysisPlan));
            caseNode.set("fidelity", mapper.valueToTree(fidelity));
            if (!specs.isEmpty()) {
                caseNode.put("sql", specs.get(0).sql());
            }
            caseReports.add(caseNode);
        }

        return new BenchmarkSummary(
                cases.size(),
                measureOk, aggOk, partitionOk, orderingOk, limitOk,
                relationshipOk, timeGrainOk, allOk,
                caseReports);
    }

    public static List<BenchmarkCase> loadLastCases(Path gapAnalysisPath, Path shadowLogPath, int limit)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<Integer, BenchmarkCase> byIndex = new LinkedHashMap<>();

        if (Files.exists(gapAnalysisPath)) {
            JsonNode root = mapper.readTree(gapAnalysisPath.toFile());
            if (root.isArray()) {
                for (JsonNode entry : root) {
                    int index = entry.path("index").asInt();
                    StructuredSemanticPlan plan = mapper.treeToValue(
                            entry.path("gptStructuredPlan"), StructuredSemanticPlan.class);
                    boolean valid = entry.path("runtimeValidatorValid").asBoolean(false);
                    List<String> issues = new ArrayList<>();
                    entry.path("runtimeValidatorIssues").forEach(n -> issues.add(n.asText()));
                    SemanticPlanValidationResult validation = valid
                            ? SemanticPlanValidationResult.ok()
                            : SemanticPlanValidationResult.fail(issues);
                    String tableRef = entry.path("analysisPlan").path("tableRef").asText("oil");
                    byIndex.put(index, new BenchmarkCase(
                            index,
                            entry.path("question").asText(),
                            plan,
                            validation,
                            tableRef));
                }
            }
        }

        if (Files.exists(shadowLogPath)) {
            List<String> lines = Files.readAllLines(shadowLogPath);
            int shadowIndex = byIndex.size() + 1;
            for (String line : lines) {
                if (line.isBlank()) continue;
                JsonNode entry = mapper.readTree(line);
                StructuredSemanticPlan plan = mapper.treeToValue(
                        entry.path("gptStructuredPlan"), StructuredSemanticPlan.class);
                boolean valid = entry.path("gptValidationValid").asBoolean(false);
                List<String> issues = new ArrayList<>();
                entry.path("gptValidationIssues").forEach(n -> issues.add(n.asText()));
                SemanticPlanValidationResult validation = valid
                        ? SemanticPlanValidationResult.ok()
                        : SemanticPlanValidationResult.fail(issues);
                String tableRef = entry.path("legacyAnalysisPlan").path("tableRef").asText("oil");
                byIndex.put(shadowIndex++, new BenchmarkCase(
                        shadowIndex - 1,
                        entry.path("question").asText(),
                        plan,
                        validation,
                        tableRef));
            }
        }

        List<BenchmarkCase> all = new ArrayList<>(byIndex.values());
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }

    public static void writeReport(BenchmarkSummary summary, Path outputPath, ObjectMapper mapper)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCases", summary.totalCases());
        ObjectNode rates = root.putObject("preservationRates");
        rates.put("measurePreserved", rate(summary.measurePreserved(), summary.totalCases()));
        rates.put("aggregationPreserved", rate(summary.aggregationPreserved(), summary.totalCases()));
        rates.put("partitionPreserved", rate(summary.partitionPreserved(), summary.totalCases()));
        rates.put("orderingPreserved", rate(summary.orderingPreserved(), summary.totalCases()));
        rates.put("limitPreserved", rate(summary.limitPreserved(), summary.totalCases()));
        rates.put("relationshipOperandsPreserved",
                rate(summary.relationshipOperandsPreserved(), summary.totalCases()));
        rates.put("timeGrainPreserved", rate(summary.timeGrainPreserved(), summary.totalCases()));
        rates.put("allPreserved", rate(summary.allPreserved(), summary.totalCases()));

        ArrayNode cases = root.putArray("cases");
        summary.cases().forEach(cases::add);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
    }

    private static double rate(int preserved, int total) {
        if (total == 0) return 0.0;
        return 100.0 * preserved / total;
    }

    private static List<QuerySpec> resolveQuerySpecs(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            ApprovedCatalogueSnapshot catalogue,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle,
            DeterministicAnalyticalQueryPlanner sqlPlanner
    ) {
        if (analysisPlan == null || !analysisPlan.executable()) {
            return List.of();
        }
        if (SemanticPlanScalarSqlBuilder.isScalarFiltered(plan)) {
            return List.of(SemanticPlanScalarSqlBuilder.build(catalogue.qualifiedTableName(), plan));
        }
        List<QuerySpec> base = sqlPlanner.plan(analysisPlan, bundle);
        return SemanticPlanFilterAugmenter.applyFilters(base, plan.filters());
    }
}
