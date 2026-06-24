package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityBenchmarkRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Field-level audit: {@link StructuredSemanticPlan} → {@link CanonicalQueryModel} → {@link AnalysisPlan}.
 */
public final class AdapterFieldMutationAnalyzer {

    public enum FieldStatus {
        PRESERVED,
        MODIFIED,
        INFERRED,
        DEFAULTED,
        DROPPED
    }

    public record FieldAudit(
            String field,
            Object plannerValue,
            Object canonicalValue,
            Object analysisPlanValue,
            FieldStatus plannerToCanonicalStatus,
            FieldStatus canonicalToAnalysisStatus,
            FieldStatus plannerToAnalysisStatus,
            String plannerToCanonicalMethod,
            String canonicalToAnalysisMethod,
            String plannerToAnalysisMethod
    ) {}

    public record CaseAudit(
            int index,
            String question,
            String source,
            String plannerIntent,
            String analysisIntent,
            List<FieldAudit> fields
    ) {}

    public record Summary(
            int totalCases,
            Map<String, Map<String, StatusCount>> fieldStatusCounts,
            Map<String, Map<String, MethodCount>> responsibleMethods,
            List<CaseAudit> cases
    ) {}

    public record StatusCount(String status, int count) {}

    public record MethodCount(String method, int count, List<String> sampleQuestions) {}

    private final CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();
    private final SemanticPlanToAnalysisPlanAdapter analysisAdapter;

    public AdapterFieldMutationAnalyzer(SemanticPlanToAnalysisPlanAdapter analysisAdapter) {
        this.analysisAdapter = analysisAdapter;
    }

    public Summary analyze(
            List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases,
            ApprovedCatalogueSnapshot catalogue
    ) {
        List<CaseAudit> audits = new ArrayList<>();
        for (SqlFidelityBenchmarkRunner.BenchmarkCase c : cases) {
            CanonicalQueryModel canonical = canonicalAdapter.adapt(c.plan());
            AnalysisPlan analysisPlan = analysisAdapter.toAnalysisPlan(
                    c.question(), c.tableRef(), c.plan(), c.validation(), catalogue);
            audits.add(auditCase(c, canonical, analysisPlan));
        }
        return new Summary(cases.size(), aggregateStatus(audits), aggregateMethods(audits), audits);
    }

    private CaseAudit auditCase(
            SqlFidelityBenchmarkRunner.BenchmarkCase c,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan
    ) {
        StructuredSemanticPlan plan = c.plan();
        StructuredPlanProjection projection = analysisPlan.structuredProjection();

        List<FieldAudit> fields = List.of(
                auditMetric(plan, canonical, analysisPlan),
                auditSecondaryMetric(plan, canonical, analysisPlan),
                auditAggregation(plan, canonical, projection),
                auditDimension(plan, canonical, analysisPlan, projection),
                auditFilters(plan, canonical, analysisPlan),
                auditOrdering(plan, canonical, projection),
                auditLimit(plan, canonical, projection),
                auditRelationshipVariable(plan, canonical, analysisPlan),
                auditTimeGrain(plan, canonical, projection, c.tableRef()));

        return new CaseAudit(
                c.index(),
                c.question(),
                c.source(),
                plan.intent(),
                analysisPlan.intent() != null ? analysisPlan.intent().name() : null,
                fields);
    }

    private FieldAudit auditMetric(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan
    ) {
        Object planner = plan.metric();
        Object canonicalValue = canonical.measure() != null ? canonical.measure().column() : null;
        Object analysis = analysisPlan.primaryMetric();

        FieldStatus toCanonical = classify(planner, canonicalValue);
        FieldStatus toAnalysis = classify(canonicalValue, analysis);
        FieldStatus overall = classify(planner, analysis);

        String canonicalMethod = "CanonicalQueryModelAdapter.adapt():24";
        String analysisMethod = overall == FieldStatus.PRESERVED
                ? "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():87"
                : methodForMetric(plan, analysisPlan, overall);

        return new FieldAudit(
                "metric", planner, canonicalValue, analysis,
                toCanonical, toAnalysis, overall,
                canonicalMethod,
                toAnalysis == FieldStatus.PRESERVED
                        ? "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():87"
                        : analysisMethod,
                analysisMethod);
    }

    private FieldAudit auditSecondaryMetric(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan
    ) {
        Object planner = plan.secondaryMetric();
        Object canonicalValue = canonical.metadata() != null ? canonical.metadata().secondaryMetric() : null;
        Object analysis = analysisPlan.secondaryMetric();

        return buildFieldAudit(
                "secondaryMetric",
                planner, canonicalValue, analysis,
                "CanonicalQueryModelAdapter.adapt():65",
                "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():93",
                "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():93");
    }

    private FieldAudit auditAggregation(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection
    ) {
        Object planner = plan.aggregations() != null ? plan.aggregations().primary() : null;
        Object canonicalValue = canonical.measure() != null ? canonical.measure().aggregation() : null;
        Object analysis = projection != null ? projection.primaryAggregation() : null;

        FieldStatus overall = classify(planner, analysis);
        String analysisMethod = "SemanticPlanToAnalysisPlanAdapter.buildProjection():120";
        if (overall == FieldStatus.INFERRED) {
            analysisMethod = "SemanticPlanToAnalysisPlanAdapter.buildProjection():111";
        }

        return buildFieldAudit(
                "aggregation",
                planner, canonicalValue, analysis,
                "CanonicalQueryModelAdapter.adapt():24",
                analysisMethod,
                analysisMethod);
    }

    private FieldAudit auditDimension(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan,
            StructuredPlanProjection projection
    ) {
        Object planner = primaryDimension(plan);
        Object canonicalValue = canonical.partition() != null ? canonical.partition().column() : null;
        Object analysis = analysisPlan.dimension();

        FieldStatus overall = classifyDimension(planner, analysis, plan, analysisPlan);
        String analysisMethod = resolveDimensionMethod(plan, analysisPlan, overall);

        FieldStatus toAnalysis = classify(canonicalValue, analysis);
        if (overall == FieldStatus.DROPPED && plan.dimensions() != null && !plan.dimensions().isEmpty()
                && analysisPlan.intent() == AnalysisIntent.RELATIONSHIP) {
            toAnalysis = FieldStatus.DROPPED;
        }

        return new FieldAudit(
                "dimension",
                planner,
                canonicalValue,
                analysis,
                classify(planner, canonicalValue),
                toAnalysis,
                overall,
                "CanonicalQueryModelAdapter.adapt():30",
                analysisMethod,
                analysisMethod);
    }

    private FieldAudit auditFilters(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan
    ) {
        Object planner = serializeFilters(plan.filters());
        Object canonicalValue = serializeCanonicalFilters(canonical);
        Object analysis = null;

        FieldStatus toAnalysis = planner == null || "[]".equals(planner)
                ? FieldStatus.PRESERVED
                : FieldStatus.DROPPED;

        return new FieldAudit(
                "filters",
                planner, canonicalValue, analysis,
                classify(planner, canonicalValue),
                toAnalysis,
                toAnalysis,
                "CanonicalQueryModelAdapter.adapt():35",
                "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():87",
                "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():87");
    }

    private FieldAudit auditOrdering(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection
    ) {
        Object planner = serializeOrdering(plan.ordering());
        Object canonicalValue = canonical.ordering() != null
                ? orderLabel(canonical.ordering().column(), canonical.ordering().direction())
                : null;
        Object analysis = projection != null
                ? orderLabel(projection.orderColumn(), projection.orderDirection())
                : null;

        return buildFieldAudit(
                "ordering",
                planner, canonicalValue, analysis,
                "CanonicalQueryModelAdapter.adapt():56",
                "SemanticPlanToAnalysisPlanAdapter.buildProjection():113",
                "SemanticPlanToAnalysisPlanAdapter.buildProjection():113");
    }

    private FieldAudit auditLimit(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection
    ) {
        Object planner = plan.limit();
        Object canonicalValue = canonical.limit();
        Object analysis = projection != null ? projection.resultLimit() : null;

        return buildFieldAudit(
                "limit",
                planner, canonicalValue, analysis,
                "CanonicalQueryModelAdapter.adapt():69",
                "SemanticPlanToAnalysisPlanAdapter.buildProjection():126",
                "SemanticPlanToAnalysisPlanAdapter.buildProjection():126");
    }

    private FieldAudit auditRelationshipVariable(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan
    ) {
        Object planner = plan.relationshipVariable();
        Object canonicalValue = canonical.metadata() != null
                ? canonical.metadata().relationshipVariable()
                : null;
        Object analysis = analysisPlan.relationshipVariable();

        FieldStatus overall = classify(planner, analysis);
        String analysisMethod = "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():87";

        if (analysisPlan.intent() == AnalysisIntent.RELATIONSHIP) {
            SemanticPlanToAnalysisPlanAdapter.RelationshipOperands operands =
                    SemanticPlanToAnalysisPlanAdapter.resolveRelationshipOperands(plan);
            if (operands.valid() && !eq(planner, operands.secondary())) {
                overall = FieldStatus.MODIFIED;
                analysisMethod = "SemanticPlanToAnalysisPlanAdapter.resolveRelationshipOperands():151";
            } else if (!operands.valid()) {
                overall = FieldStatus.MODIFIED;
                analysisMethod = "SemanticPlanToAnalysisPlanAdapter.resolveRelationshipOperands():164";
            } else {
                analysisMethod = "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():67";
            }
            analysis = operands.valid() ? operands.secondary() : analysis;
        }

        FieldStatus toAnalysis = classify(canonicalValue, analysis);
        if (overall == FieldStatus.MODIFIED) {
            toAnalysis = FieldStatus.MODIFIED;
        }

        return new FieldAudit(
                "relationshipVariable",
                planner, canonicalValue, analysis,
                classify(planner, canonicalValue),
                toAnalysis,
                overall,
                "CanonicalQueryModelAdapter.adapt():66",
                analysisMethod,
                analysisMethod);
    }

    private FieldAudit auditTimeGrain(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection,
            String tableRef
    ) {
        Object planner = plan.timeGrain();
        Object canonicalValue = canonical.partition() != null ? canonical.partition().timeGrain() : null;
        Object analysis = projection != null ? projection.timeGrain() : null;

        FieldStatus overall = classify(planner, analysis);
        String analysisMethod = "SemanticPlanToAnalysisPlanAdapter.buildProjection():120";

        if ((planner == null || String.valueOf(planner).isBlank()) && analysis != null) {
            overall = FieldStatus.INFERRED;
            analysisMethod = "SemanticPlanToAnalysisPlanAdapter.inferTimeGrain():130";
        }

        return buildFieldAudit(
                "timeGrain",
                planner, canonicalValue, analysis,
                "CanonicalQueryModelAdapter.adapt():32",
                analysisMethod,
                analysisMethod);
    }

    private FieldAudit buildFieldAudit(
            String field,
            Object planner,
            Object canonical,
            Object analysis,
            String canonicalMethod,
            String analysisMethod,
            String overallMethod
    ) {
        return new FieldAudit(
                field, planner, canonical, analysis,
                classify(planner, canonical),
                classify(canonical, analysis),
                classify(planner, analysis),
                canonicalMethod, analysisMethod, overallMethod);
    }

    private static FieldStatus classify(Object expected, Object actual) {
        if (expected == null || (expected instanceof String s && s.isBlank())) {
            if (actual == null || (actual instanceof String a && a.isBlank())) {
                return FieldStatus.PRESERVED;
            }
            return FieldStatus.INFERRED;
        }
        if (actual == null || (actual instanceof String a && a.isBlank())) {
            return FieldStatus.DROPPED;
        }
        if (valuesEqual(expected, actual)) {
            return FieldStatus.PRESERVED;
        }
        return FieldStatus.MODIFIED;
    }

    private static FieldStatus classifyDimension(
            Object planner,
            Object analysis,
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan
    ) {
        if (analysisPlan.intent() == AnalysisIntent.RELATIONSHIP
                && plan.dimensions() != null && !plan.dimensions().isEmpty()
                && (analysis == null || String.valueOf(analysis).isBlank())) {
            return FieldStatus.DROPPED;
        }
        if (analysisPlan.intent() == AnalysisIntent.CONTRIBUTION
                && (planner == null || String.valueOf(planner).isBlank())
                && "composition".equalsIgnoreCase(String.valueOf(analysisPlan.groupingAlias()))) {
            return FieldStatus.DEFAULTED;
        }
        return classify(planner, analysis);
    }

    private static String resolveDimensionMethod(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            FieldStatus status
    ) {
        if (status == FieldStatus.DROPPED && analysisPlan.intent() == AnalysisIntent.RELATIONSHIP) {
            return "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():66";
        }
        if (status == FieldStatus.DEFAULTED) {
            return "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():77";
        }
        if (status == FieldStatus.MODIFIED) {
            return "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():54";
        }
        return "SemanticPlanToAnalysisPlanAdapter.buildProjection():110";
    }

    private static String methodForMetric(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            FieldStatus status
    ) {
        if (analysisPlan.intent() == AnalysisIntent.RELATIONSHIP && status == FieldStatus.MODIFIED) {
            return "SemanticPlanToAnalysisPlanAdapter.resolveRelationshipOperands():151";
        }
        return "SemanticPlanToAnalysisPlanAdapter.toAnalysisPlan():87";
    }

    private static Object primaryDimension(StructuredSemanticPlan plan) {
        if (plan.dimensions() == null || plan.dimensions().isEmpty()) {
            return null;
        }
        return plan.dimensions().get(0);
    }

    private static Object serializeFilters(List<StructuredSemanticPlan.SemanticFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "[]";
        }
        return filters.stream()
                .map(f -> f.column() + " " + f.operator() + " " + f.value())
                .toList();
    }

    private static Object serializeCanonicalFilters(CanonicalQueryModel canonical) {
        if (canonical.filters() == null || canonical.filters().isEmpty()) {
            return "[]";
        }
        return canonical.filters().stream()
                .map(f -> f.column() + " " + f.operator() + " " + f.value())
                .toList();
    }

    private static Object serializeOrdering(StructuredSemanticPlan.SemanticOrdering ordering) {
        if (ordering == null) {
            return null;
        }
        return orderLabel(ordering.column(), ordering.direction());
    }

    private static String orderLabel(String column, String direction) {
        if (column == null) return null;
        if (direction == null || direction.isBlank()) return column;
        return column + " " + direction.toUpperCase(Locale.ROOT);
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (Objects.equals(a, b)) return true;
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            return la.equals(lb);
        }
        if (a == null || b == null) return false;
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    private static boolean eq(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    private static Map<String, Map<String, StatusCount>> aggregateStatus(List<CaseAudit> cases) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (CaseAudit c : cases) {
            for (FieldAudit f : c.fields()) {
                counts.computeIfAbsent(f.field(), k -> new LinkedHashMap<>())
                        .merge(f.plannerToAnalysisStatus().name(), 1, Integer::sum);
            }
        }
        Map<String, Map<String, StatusCount>> result = new LinkedHashMap<>();
        counts.forEach((field, statusMap) -> {
            Map<String, StatusCount> row = new LinkedHashMap<>();
            statusMap.forEach((status, count) -> row.put(status, new StatusCount(status, count)));
            result.put(field, row);
        });
        return result;
    }

    private static Map<String, Map<String, MethodCount>> aggregateMethods(List<CaseAudit> cases) {
        Map<String, Map<String, MethodCountBuilder>> builders = new LinkedHashMap<>();
        for (CaseAudit c : cases) {
            for (FieldAudit f : c.fields()) {
                if (f.plannerToAnalysisStatus() == FieldStatus.PRESERVED) {
                    continue;
                }
                builders.computeIfAbsent(f.field(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(f.plannerToAnalysisMethod(), k -> new MethodCountBuilder())
                        .add(c.question());
            }
        }
        Map<String, Map<String, MethodCount>> result = new LinkedHashMap<>();
        builders.forEach((field, methodMap) -> {
            Map<String, MethodCount> row = new LinkedHashMap<>();
            methodMap.forEach((method, builder) ->
                    row.put(method, builder.build(method)));
            result.put(field, row);
        });
        return result;
    }

    public static void writeJsonReport(Summary summary, Path outputPath, ObjectMapper mapper) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCases", summary.totalCases());

        ObjectNode statusSummary = root.putObject("plannerToAnalysisStatusByField");
        summary.fieldStatusCounts().forEach((field, statuses) -> {
            ObjectNode fieldNode = statusSummary.putObject(field);
            statuses.forEach((status, sc) -> fieldNode.put(sc.status(), sc.count()));
        });

        ObjectNode methods = root.putObject("responsibleMethodsByField");
        summary.responsibleMethods().forEach((field, methodMap) -> {
            ArrayNode arr = methods.putArray(field);
            methodMap.values().stream()
                    .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                    .forEach(mc -> {
                        ObjectNode node = arr.addObject();
                        node.put("method", mc.method());
                        node.put("count", mc.count());
                        ArrayNode samples = node.putArray("sampleQuestions");
                        mc.sampleQuestions().stream().limit(5).forEach(samples::add);
                    });
        });

        ArrayNode cases = root.putArray("cases");
        for (CaseAudit c : summary.cases()) {
            ObjectNode caseNode = cases.addObject();
            caseNode.put("index", c.index());
            caseNode.put("question", c.question());
            caseNode.put("source", c.source());
            caseNode.put("plannerIntent", c.plannerIntent());
            caseNode.put("analysisIntent", c.analysisIntent());

            ArrayNode fields = caseNode.putArray("fields");
            for (FieldAudit f : c.fields()) {
                ObjectNode fieldNode = fields.addObject();
                fieldNode.put("field", f.field());
                fieldNode.set("plannerValue", mapper.valueToTree(f.plannerValue()));
                fieldNode.set("canonicalValue", mapper.valueToTree(f.canonicalValue()));
                fieldNode.set("analysisPlanValue", mapper.valueToTree(f.analysisPlanValue()));
                fieldNode.put("plannerToCanonicalStatus", f.plannerToCanonicalStatus().name());
                fieldNode.put("canonicalToAnalysisStatus", f.canonicalToAnalysisStatus().name());
                fieldNode.put("plannerToAnalysisStatus", f.plannerToAnalysisStatus().name());
                fieldNode.put("plannerToCanonicalMethod", f.plannerToCanonicalMethod());
                fieldNode.put("canonicalToAnalysisMethod", f.canonicalToAnalysisMethod());
                fieldNode.put("plannerToAnalysisMethod", f.plannerToAnalysisMethod());
            }
        }

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
    }

    public static void writeMarkdownSummary(Summary summary, Path outputPath) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# Adapter Field Mutation Summary\n\n");
        md.append("Audit path: `StructuredSemanticPlan` → `CanonicalQueryModel` → `AnalysisPlan`\n\n");
        md.append("Total cases: ").append(summary.totalCases()).append("\n\n");

        md.append("## Planner → AnalysisPlan status by field\n\n");
        md.append("| Field | Preserved | Modified | Inferred | Defaulted | Dropped |\n");
        md.append("|-------|-----------|----------|----------|-----------|--------|\n");
        for (String field : List.of(
                "metric", "secondaryMetric", "aggregation", "dimension", "filters",
                "ordering", "limit", "relationshipVariable", "timeGrain")) {
            Map<String, StatusCount> counts = summary.fieldStatusCounts().getOrDefault(field, Map.of());
            md.append("| ").append(field)
                    .append(" | ").append(count(counts, "PRESERVED"))
                    .append(" | ").append(count(counts, "MODIFIED"))
                    .append(" | ").append(count(counts, "INFERRED"))
                    .append(" | ").append(count(counts, "DEFAULTED"))
                    .append(" | ").append(count(counts, "DROPPED"))
                    .append(" |\n");
        }
        md.append("\n");

        md.append("## CanonicalQueryModel fidelity\n\n");
        md.append("The canonical adapter is a pure translation layer. Any non-`PRESERVED` ");
        md.append("planner→canonical status indicates a mapping gap in `CanonicalQueryModelAdapter`.\n\n");

        long canonicalDrift = summary.cases().stream()
                .flatMap(c -> c.fields().stream())
                .filter(f -> f.plannerToCanonicalStatus() != FieldStatus.PRESERVED)
                .count();
        md.append("Planner→canonical non-preserved field observations: ")
                .append(canonicalDrift).append("\n\n");

        md.append("## Responsible methods (planner → AnalysisPlan mutations only)\n\n");
        summary.responsibleMethods().forEach((field, methods) -> {
            md.append("### ").append(field).append("\n\n");
            if (methods.isEmpty()) {
                md.append("All cases preserved.\n\n");
                return;
            }
            md.append("| Method | Count | Sample questions |\n");
            md.append("|--------|-------|------------------|\n");
            methods.values().stream()
                    .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                    .forEach(mc -> {
                        md.append("| `").append(mc.method()).append("` | ")
                                .append(mc.count()).append(" | ");
                        if (mc.sampleQuestions().size() <= 2) {
                            md.append(String.join("; ", mc.sampleQuestions()));
                        } else {
                            md.append(mc.sampleQuestions().size()).append(" questions");
                        }
                        md.append(" |\n");
                    });
            md.append("\n");
        });

        md.append("## Architectural conclusion\n\n");
        long analysisDrift = summary.cases().stream()
                .flatMap(c -> c.fields().stream())
                .filter(f -> f.canonicalToAnalysisStatus() != FieldStatus.PRESERVED)
                .count();
        md.append("- Planner→canonical drift observations: ").append(canonicalDrift).append("\n");
        md.append("- Canonical→AnalysisPlan drift observations: ").append(analysisDrift).append("\n");
        md.append("- Filters are not represented on `AnalysisPlan`; they are dropped at adapter output.\n");
        md.append("- `relationshipVariable` may be rewritten by `resolveRelationshipOperands()`.\n");
        md.append("- `timeGrain` may be inferred by `inferTimeGrain()` when absent on the planner.\n");
        md.append("- `dimension` may be dropped for `RELATIONSHIP` or defaulted to `composition` for scalar contribution.\n");

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        Files.writeString(outputPath, md);
    }

    private static int count(Map<String, StatusCount> counts, String status) {
        StatusCount sc = counts.get(status);
        return sc != null ? sc.count() : 0;
    }

    private static final class MethodCountBuilder {
        private int count = 0;
        private final List<String> questions = new ArrayList<>();

        private void add(String question) {
            count++;
            if (!questions.contains(question) && questions.size() < 5) {
                questions.add(question);
            }
        }

        private MethodCount build(String method) {
            return new MethodCount(method, count, List.copyOf(questions));
        }
    }
}
