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
 * Benchmark runner comparing legacy SQL path vs canonical SQL renderer (shadow only).
 */
public final class CanonicalSqlDiffBenchmarkRunner {

    private CanonicalSqlDiffBenchmarkRunner() {}

    public record DiffCase(
            int index,
            String question,
            String source,
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            CanonicalQueryValidationResult canonicalValidation,
            String legacySql,
            String canonicalSql,
            boolean sqlTextMatch,
            SqlFidelityReport.Result canonicalSelfFidelity,
            SqlFidelityReport.Result legacyVsCanonicalFidelity,
            String canonicalRenderError
    ) {}

    public record DiffSummary(
            int totalCases,
            int canonicalRenderable,
            int canonicalValidationPass,
            int sqlTextMatchCount,
            int canonicalSelfFidelityPass,
            int legacyVsCanonicalFidelityPass,
            Map<String, Integer> legacyVsCanonicalMutationFields,
            List<ObjectNode> cases
    ) {}

    public static DiffSummary run(
            List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases,
            ApprovedCatalogueSnapshot catalogue,
            SemanticPlanToAnalysisPlanAdapter analysisAdapter,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle,
            CanonicalQueryModelAdapter canonicalAdapter,
            CanonicalQueryValidator canonicalValidator,
            CanonicalSqlRenderer canonicalRenderer,
            ObjectMapper mapper
    ) {
        List<ObjectNode> caseNodes = new ArrayList<>();
        int renderable = 0;
        int validationPass = 0;
        int textMatch = 0;
        int selfFidelityPass = 0;
        int legacyFidelityPass = 0;
        Map<String, Integer> mutationFields = new LinkedHashMap<>();

        for (SqlFidelityBenchmarkRunner.BenchmarkCase c : cases) {
            CanonicalQueryModel canonical = canonicalAdapter.adapt(c.plan());
            CanonicalQueryValidationResult validation = canonicalValidator.validate(canonical, catalogue);

            AnalysisPlan analysisPlan = analysisAdapter.toAnalysisPlan(
                    c.question(), c.tableRef(), c.plan(), c.validation(), catalogue);
            String legacySql = resolveLegacySql(c, analysisPlan, catalogue, sqlPlanner, bundle);

            String canonicalSql = null;
            String renderError = null;
            if (validation.valid()) {
                validationPass++;
                try {
                    QuerySpec spec = canonicalRenderer.render(canonical, catalogue.qualifiedTableName());
                    canonicalSql = spec.sql();
                    renderable++;
                } catch (Exception e) {
                    renderError = e.getMessage();
                }
            }

            boolean sqlMatch = canonicalSql != null && CanonicalSqlDiffComparator.sqlEquals(legacySql, canonicalSql);
            if (sqlMatch) {
                textMatch++;
            }

            SqlFidelityReport.Result selfFidelity = canonicalSql != null
                    ? SqlFidelityReport.compare(canonical, canonicalSql)
                    : emptyFidelity();
            if (selfFidelity.allMatch()) {
                selfFidelityPass++;
            }

            SqlFidelityReport.Result legacyFidelity = canonicalSql != null
                    ? SqlFidelityReport.compare(canonical, legacySql)
                    : emptyFidelity();
            if (legacyFidelity.allMatch()) {
                legacyFidelityPass++;
            }
            for (SqlFidelityReport.Mutation mutation : legacyFidelity.mutations()) {
                mutationFields.merge(mutation.field(), 1, Integer::sum);
            }

            ObjectNode node = mapper.createObjectNode();
            node.put("index", c.index());
            node.put("question", c.question());
            node.put("source", c.source());
            node.set("structuredSemanticPlan", mapper.valueToTree(c.plan()));
            node.set("canonicalQueryModel", mapper.valueToTree(canonical));
            node.put("canonicalValidationValid", validation.valid());
            node.set("canonicalValidationIssues", mapper.valueToTree(validation.issues()));
            node.put("legacySql", legacySql);
            node.put("canonicalSql", canonicalSql);
            node.put("sqlTextMatch", sqlMatch);
            node.set("canonicalSelfFidelity", mapper.valueToTree(selfFidelity));
            node.set("legacyVsCanonicalFidelity", mapper.valueToTree(legacyFidelity));
            if (renderError != null) {
                node.put("canonicalRenderError", renderError);
            }
            caseNodes.add(node);
        }

        return new DiffSummary(
                cases.size(),
                renderable,
                validationPass,
                textMatch,
                selfFidelityPass,
                legacyFidelityPass,
                mutationFields,
                caseNodes);
    }

    public static void writeJsonReport(DiffSummary summary, Path outputPath, ObjectMapper mapper)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCases", summary.totalCases());
        root.put("canonicalValidationPass", summary.canonicalValidationPass());
        root.put("canonicalRenderable", summary.canonicalRenderable());
        root.put("sqlTextMatchCount", summary.sqlTextMatchCount());
        root.put("canonicalSelfFidelityPass", summary.canonicalSelfFidelityPass());
        root.put("legacyVsCanonicalFidelityPass", summary.legacyVsCanonicalFidelityPass());
        root.put("sqlTextMatchRate", rate(summary.sqlTextMatchCount(), summary.totalCases()));
        root.put("canonicalSelfFidelityRate", rate(summary.canonicalSelfFidelityPass(), summary.totalCases()));
        root.put("legacyVsCanonicalFidelityRate",
                rate(summary.legacyVsCanonicalFidelityPass(), summary.totalCases()));

        ObjectNode mutations = root.putObject("legacyVsCanonicalMutationFields");
        summary.legacyVsCanonicalMutationFields().forEach(mutations::put);

        ArrayNode cases = root.putArray("cases");
        summary.cases().forEach(cases::add);

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
    }

    public static void writeMarkdownSummary(DiffSummary summary, Path outputPath) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# Canonical SQL Diff Summary\n\n");
        md.append("Shadow comparison: legacy renderer vs `CanonicalSqlRenderer`.\n\n");
        md.append("Total cases: ").append(summary.totalCases()).append("\n\n");
        md.append("| Metric | Count | Rate |\n");
        md.append("|--------|-------|------|\n");
        md.append(row("Canonical validation pass", summary.canonicalValidationPass(), summary.totalCases()));
        md.append(row("Canonical SQL rendered", summary.canonicalRenderable(), summary.totalCases()));
        md.append(row("Exact SQL text match", summary.sqlTextMatchCount(), summary.totalCases()));
        md.append(row("Canonical self-fidelity pass", summary.canonicalSelfFidelityPass(), summary.totalCases()));
        md.append(row("Legacy vs canonical fidelity pass", summary.legacyVsCanonicalFidelityPass(),
                summary.totalCases()));
        md.append("\n");

        md.append("## Legacy vs canonical mutation fields\n\n");
        if (summary.legacyVsCanonicalMutationFields().isEmpty()) {
            md.append("No semantic mismatches detected.\n\n");
        } else {
            md.append("| Field | Count |\n");
            md.append("|-------|-------|\n");
            summary.legacyVsCanonicalMutationFields().entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(e -> md.append("| ").append(e.getKey()).append(" | ")
                            .append(e.getValue()).append(" |\n"));
            md.append("\n");
        }

        md.append("## Interpretation\n\n");
        md.append("- **Canonical self-fidelity** measures whether `CanonicalSqlRenderer` faithfully implements ");
        md.append("`CanonicalQueryModel` (target: 100%).\n");
        md.append("- **Legacy vs canonical fidelity** measures semantic alignment between the current ");
        md.append("template path and planner-faithful canonical SQL.\n");
        md.append("- **SQL text match** is strict normalized string equality (expected to be low during shadow).\n");

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        Files.writeString(outputPath, md);
    }

    private static String row(String label, int count, int total) {
        return "| " + label + " | " + count + " | "
                + String.format("%.0f%%", rate(count, total)) + " |\n";
    }

    private static double rate(int count, int total) {
        if (total == 0) return 0.0;
        return 100.0 * count / total;
    }

    private static SqlFidelityReport.Result emptyFidelity() {
        return new SqlFidelityReport.Result(
                false, false, false, false, false, false, false, List.of());
    }

    private static String resolveLegacySql(
            SqlFidelityBenchmarkRunner.BenchmarkCase c,
            AnalysisPlan analysisPlan,
            ApprovedCatalogueSnapshot catalogue,
            DeterministicAnalyticalQueryPlanner sqlPlanner,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle bundle
    ) {
        if (c.sql() != null && !c.sql().isBlank()) {
            return c.sql();
        }
        if (analysisPlan == null || !analysisPlan.executable()) {
            return null;
        }
        if (SemanticPlanScalarSqlBuilder.isScalarFiltered(c.plan())) {
            return SemanticPlanScalarSqlBuilder.build(catalogue.qualifiedTableName(), c.plan()).sql();
        }
        List<QuerySpec> specs = SemanticPlanFilterAugmenter.applyFilters(
                sqlPlanner.plan(analysisPlan, bundle), c.plan().filters());
        return specs.isEmpty() ? null : specs.get(0).sql();
    }
}
