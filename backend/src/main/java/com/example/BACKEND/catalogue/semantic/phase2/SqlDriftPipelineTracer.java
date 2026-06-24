package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AggregationSpec;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DimensionBucketingSql;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.GroupedMetricSqlBuilder;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.IntentAggregationStrategy;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.RankingSqlTemplate;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlRenderHints;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlRenderPostProcessor;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityReport;
import com.example.BACKEND.catalogue.semantic.canonical.SqlInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Traces semantic fields through the production planning/SQL pipeline and locates
 * the earliest checkpoint where planner meaning diverges from downstream artifacts.
 */
public final class SqlDriftPipelineTracer {

    private final CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();
    private final IntentAggregationStrategy aggregationStrategy;
    private final GroupedMetricSqlBuilder groupedMetricSqlBuilder;
    private final RankingSqlTemplate rankingSqlTemplate;

    public SqlDriftPipelineTracer(
            IntentAggregationStrategy aggregationStrategy,
            GroupedMetricSqlBuilder groupedMetricSqlBuilder,
            RankingSqlTemplate rankingSqlTemplate
    ) {
        this.aggregationStrategy = aggregationStrategy;
        this.groupedMetricSqlBuilder = groupedMetricSqlBuilder;
        this.rankingSqlTemplate = rankingSqlTemplate;
    }

    public record PipelineCheckpoint(
            String stage,
            String className,
            String methodName,
            String sourceFile,
            int lineNumber,
            String field,
            String value
    ) {
        public String location() {
            return className + "." + methodName + "():" + lineNumber;
        }
    }

    public record FieldTrace(
            String field,
            String expected,
            String actual,
            PipelineCheckpoint firstMutation,
            List<PipelineCheckpoint> executionPath
    ) {}

    public List<FieldTrace> trace(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Result sqlFidelity
    ) {
        CanonicalQueryModel canonical = canonicalAdapter.adapt(plan);
        List<FieldTrace> traces = new ArrayList<>();

        for (SqlFidelityReport.Mutation mutation : sqlFidelity.mutations()) {
            FieldTrace trace = switch (mutation.field()) {
                case "aggregation" -> traceAggregation(plan, canonical, analysisPlan, generatedSql, mutation);
                case "relationshipOperands" -> traceRelationship(plan, canonical, analysisPlan, generatedSql, mutation);
                case "ordering" -> traceOrdering(plan, analysisPlan, generatedSql, mutation);
                case "limit" -> traceLimit(plan, analysisPlan, generatedSql, mutation);
                case "partition" -> tracePartition(plan, analysisPlan, generatedSql, mutation);
                case "measure" -> traceMeasure(plan, analysisPlan, generatedSql, mutation);
                case "timeGrain" -> traceTimeGrain(plan, analysisPlan, generatedSql, mutation);
                default -> firstMutation(mutation.field(), mutation.expected(), mutation.actual(), List.of());
            };
            traces.add(trace);
        }
        return traces;
    }

    private FieldTrace traceAggregation(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Mutation mutation
    ) {
        String expected = mutation.expected();
        List<PipelineCheckpoint> path = new ArrayList<>();

        path.add(cp("StructuredSemanticPlan", "StructuredSemanticPlan", "aggregations",
                "StructuredSemanticPlan.java", 14, "aggregation", plannerAggregation(plan)));
        path.add(cp("CanonicalQueryModel", "CanonicalQueryModelAdapter", "adapt",
                "CanonicalQueryModelAdapter.java", 28, "aggregation",
                canonical.measure() != null ? canonical.measure().aggregation() : null));

        StructuredPlanProjection projection = analysisPlan.structuredProjection();
        path.add(cp("StructuredPlanProjection", "SemanticPlanToAnalysisPlanAdapter", "buildProjection",
                "SemanticPlanToAnalysisPlanAdapter.java", 120, "aggregation",
                projection != null ? projection.primaryAggregation() : null));

        SqlRenderHints hints = SqlRenderHints.fromAnalysisPlan(analysisPlan);
        path.add(cp("SqlRenderHints", "SqlRenderHints", "fromAnalysisPlan",
                "SqlRenderHints.java", 16, "aggregation",
                hints != null ? hints.primaryAggregation() : null));

        AnalyticalIntentKind sqlIntent = analysisPlan.intent().sqlKind();
        AggregationSpec intentSpec = aggregationStrategy.forSqlIntent(
                sqlIntent, analysisPlan.primaryMetric());
        path.add(cp("IntentAggregationStrategy", "IntentAggregationStrategy", "forSqlIntent",
                "IntentAggregationStrategy.java", 13, "aggregation", intentSpec.aggregation().name()));

        TemplateContext ctx = buildGroupedContext(analysisPlan, hints);
        AggregationSpec resolved = resolveAggregationSpec(ctx);
        path.add(cp("GroupedMetricSqlBuilder", "GroupedMetricSqlBuilder", "resolveAggregationSpec",
                "GroupedMetricSqlBuilder.java", 62, "aggregation", resolved.aggregation().name()));

        String preTemplateSql = groupedMetricSqlBuilder.renderGroupedQuery(ctx, resolved);
        path.add(cp("GroupedMetricSqlBuilder", "GroupedMetricSqlBuilder", "renderGroupedQuery",
                "GroupedMetricSqlBuilder.java", 28, "aggregation",
                SqlInspector.aggregationOnColumn(preTemplateSql, plan.metric())));

        String postProcessorSql = SqlRenderPostProcessor.apply(preTemplateSql, hints);
        path.add(cp("SqlRenderPostProcessor", "SqlRenderPostProcessor", "apply",
                "SqlRenderPostProcessor.java", 12, "aggregation",
                SqlInspector.aggregationOnColumn(postProcessorSql, plan.metric())));

        path.add(cp("GeneratedSql", templateClassForIntent(analysisPlan.intent()), "render",
                templateSourceFile(analysisPlan.intent()), templateRenderLine(analysisPlan.intent()),
                "aggregation", SqlInspector.aggregationOnColumn(generatedSql, plan.metric())));

        return firstMutation("aggregation", expected, mutation.actual(), path);
    }

    private FieldTrace traceRelationship(
            StructuredSemanticPlan plan,
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Mutation mutation
    ) {
        List<PipelineCheckpoint> path = new ArrayList<>();

        path.add(cp("StructuredSemanticPlan", "StructuredSemanticPlan", "relationshipFields",
                "StructuredSemanticPlan.java", 17, "relationshipOperands", mutation.expected()));
        path.add(cp("CanonicalQueryModel", "CanonicalQueryModelAdapter", "adapt",
                "CanonicalQueryModelAdapter.java", 52, "relationshipOperands", mutation.expected()));

        SemanticPlanToAnalysisPlanAdapter.RelationshipOperands operands =
                SemanticPlanToAnalysisPlanAdapter.resolveRelationshipOperands(plan);
        path.add(cp("resolveRelationshipOperands", "SemanticPlanToAnalysisPlanAdapter",
                "resolveRelationshipOperands", "SemanticPlanToAnalysisPlanAdapter.java", 151,
                "relationshipOperands",
                operands.valid() ? corrLabel(operands.secondary(), operands.primary()) : "unresolved"));
        path.add(cp("AnalysisPlan", "SemanticPlanToAnalysisPlanAdapter", "toAnalysisPlan",
                "SemanticPlanToAnalysisPlanAdapter.java", 63, "relationshipOperands",
                corrLabel(analysisPlan.relationshipVariable(), analysisPlan.primaryMetric())));
        path.add(cp("RelationshipSqlTemplate", "RelationshipSqlTemplate", "render",
                "RelationshipSqlTemplate.java", 18, "relationshipOperands",
                corrLabel(analysisPlan.relationshipVariable(), analysisPlan.primaryMetric())));

        SqlInspector.ParsedSql parsed = SqlInspector.parse(generatedSql);
        path.add(cp("GeneratedSql", "RelationshipSqlTemplate", "render",
                "RelationshipSqlTemplate.java", 19, "relationshipOperands",
                corrLabel(parsed.corrLeft(), parsed.corrRight())));

        return firstMutation("relationshipOperands", mutation.expected(), mutation.actual(), path);
    }

    private FieldTrace traceOrdering(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Mutation mutation
    ) {
        List<PipelineCheckpoint> path = new ArrayList<>();
        StructuredPlanProjection projection = analysisPlan.structuredProjection();
        SqlRenderHints hints = SqlRenderHints.fromAnalysisPlan(analysisPlan);
        TemplateContext ctx = buildGroupedContext(analysisPlan, hints);

        path.add(cp("StructuredSemanticPlan", "StructuredSemanticPlan", "ordering",
                "StructuredSemanticPlan.java", 15, "ordering",
                plan.ordering() != null
                        ? orderLabel(plan.ordering().column(), plan.ordering().direction()) : null));
        path.add(cp("StructuredPlanProjection", "SemanticPlanToAnalysisPlanAdapter", "buildProjection",
                "SemanticPlanToAnalysisPlanAdapter.java", 120, "ordering",
                projection != null ? orderLabel(projection.orderColumn(), projection.orderDirection()) : null));
        path.add(cp("SqlRenderHints", "SqlRenderHints", "fromAnalysisPlan",
                "SqlRenderHints.java", 16, "ordering",
                hints != null ? orderLabel(hints.orderColumn(), hints.orderDirection()) : null));

        String groupedSql = groupedMetricSqlBuilder.renderGroupedQuery(ctx);
        SqlInspector.ParsedSql groupedParsed = SqlInspector.parse(groupedSql);
        path.add(cp("GroupedMetricSqlBuilder", "GroupedMetricSqlBuilder", "renderGroupedQuery",
                "GroupedMetricSqlBuilder.java", 35, "ordering",
                orderLabel(groupedParsed.orderColumn(), groupedParsed.orderDirection())));

        String postProcessed = SqlRenderPostProcessor.apply(groupedSql, hints);
        SqlInspector.ParsedSql postParsed = SqlInspector.parse(postProcessed);
        path.add(cp("SqlRenderPostProcessor", "SqlRenderPostProcessor", "apply",
                "SqlRenderPostProcessor.java", 17, "ordering",
                orderLabel(postParsed.orderColumn(), postParsed.orderDirection())));

        String rankingSql = analysisPlan.intent() == AnalysisIntent.RANKING
                ? rankingSqlTemplate.render(ctx.withRenderHints(hints))
                : postProcessed;
        SqlInspector.ParsedSql rankingParsed = SqlInspector.parse(rankingSql);
        path.add(cp("RankingSqlTemplate", "RankingSqlTemplate", "render",
                "RankingSqlTemplate.java", 14, "ordering",
                orderLabel(rankingParsed.orderColumn(), rankingParsed.orderDirection())));

        SqlInspector.ParsedSql finalParsed = SqlInspector.parse(generatedSql);
        path.add(cp("GeneratedSql", templateClassForIntent(analysisPlan.intent()), "render",
                templateSourceFile(analysisPlan.intent()), templateRenderLine(analysisPlan.intent()),
                "ordering", orderLabel(finalParsed.orderColumn(), finalParsed.orderDirection())));

        return firstMutation("ordering", mutation.expected(), mutation.actual(), path);
    }

    private FieldTrace traceLimit(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Mutation mutation
    ) {
        List<PipelineCheckpoint> path = new ArrayList<>();
        StructuredPlanProjection projection = analysisPlan.structuredProjection();
        SqlRenderHints hints = SqlRenderHints.fromAnalysisPlan(analysisPlan);
        TemplateContext ctx = buildGroupedContext(analysisPlan, hints);

        path.add(cp("StructuredSemanticPlan", "StructuredSemanticPlan", "limit",
                "StructuredSemanticPlan.java", 16, "limit", valueOf(plan.limit())));
        path.add(cp("StructuredPlanProjection", "SemanticPlanToAnalysisPlanAdapter", "buildProjection",
                "SemanticPlanToAnalysisPlanAdapter.java", 126, "limit",
                projection != null ? valueOf(projection.resultLimit()) : null));
        path.add(cp("SqlRenderHints", "SqlRenderHints", "fromAnalysisPlan",
                "SqlRenderHints.java", 16, "limit", hints != null ? valueOf(hints.resultLimit()) : null));

        String groupedSql = groupedMetricSqlBuilder.renderGroupedQuery(ctx);
        path.add(cp("GroupedMetricSqlBuilder", "GroupedMetricSqlBuilder", "renderGroupedQuery",
                "GroupedMetricSqlBuilder.java", 59, "limit",
                valueOf(SqlInspector.parse(groupedSql).limit())));

        String postProcessed = SqlRenderPostProcessor.apply(groupedSql, hints);
        path.add(cp("SqlRenderPostProcessor", "SqlRenderPostProcessor", "apply",
                "SqlRenderPostProcessor.java", 20, "limit",
                valueOf(SqlInspector.parse(postProcessed).limit())));

        String rankingSql = analysisPlan.intent() == AnalysisIntent.RANKING
                ? rankingSqlTemplate.render(ctx.withRenderHints(hints))
                : postProcessed;
        path.add(cp("RankingSqlTemplate", "RankingSqlTemplate", "render",
                "RankingSqlTemplate.java", 22, "limit",
                valueOf(SqlInspector.parse(rankingSql).limit())));

        path.add(cp("GeneratedSql", templateClassForIntent(analysisPlan.intent()), "render",
                templateSourceFile(analysisPlan.intent()), templateRenderLine(analysisPlan.intent()),
                "limit", valueOf(SqlInspector.parse(generatedSql).limit())));

        return firstMutation("limit", mutation.expected(), mutation.actual(), path);
    }

    private FieldTrace tracePartition(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Mutation mutation
    ) {
        List<PipelineCheckpoint> path = new ArrayList<>();
        StructuredPlanProjection projection = analysisPlan.structuredProjection();
        SqlRenderHints hints = SqlRenderHints.fromAnalysisPlan(analysisPlan);
        TemplateContext ctx = buildGroupedContext(analysisPlan, hints);

        String plannerPartition = plan.dimensions() != null && !plan.dimensions().isEmpty()
                ? plan.dimensions().get(0) : null;

        path.add(cp("StructuredSemanticPlan", "StructuredSemanticPlan", "dimensions",
                "StructuredSemanticPlan.java", 12, "partition", plannerPartition));
        path.add(cp("StructuredPlanProjection", "SemanticPlanToAnalysisPlanAdapter", "buildProjection",
                "SemanticPlanToAnalysisPlanAdapter.java", 120, "partition",
                projection != null && !projection.dimensions().isEmpty()
                        ? projection.dimensions().get(0) : null));
        path.add(cp("AnalysisPlan", "SemanticPlanToAnalysisPlanAdapter", "toAnalysisPlan",
                "SemanticPlanToAnalysisPlanAdapter.java", 87, "partition", analysisPlan.dimension()));
        path.add(cp("TemplateContext", "AnalysisPlanSqlGenerator", "buildFallbackContext",
                "AnalysisPlanSqlGenerator.java", 141, "partition", ctx.dimensionColumn()));
        path.add(cp("TemplateContext", "AnalysisPlanSqlGenerator", "buildFallbackContext",
                "AnalysisPlanSqlGenerator.java", 134, "partition", ctx.bucketExpression()));

        String groupedSql = groupedMetricSqlBuilder.renderGroupedQuery(ctx);
        path.add(cp("GroupedMetricSqlBuilder", "GroupedMetricSqlBuilder", "renderGroupedQuery",
                "GroupedMetricSqlBuilder.java", 35, "partition",
                String.join(",", SqlInspector.parse(groupedSql).groupByColumns())));

        path.add(cp("GeneratedSql", templateClassForIntent(analysisPlan.intent()), "render",
                templateSourceFile(analysisPlan.intent()), templateRenderLine(analysisPlan.intent()),
                "partition", String.join(",", SqlInspector.parse(generatedSql).groupByColumns())));

        return firstMutation("partition", mutation.expected(), mutation.actual(), path);
    }

    private FieldTrace traceMeasure(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Mutation mutation
    ) {
        List<PipelineCheckpoint> path = new ArrayList<>();
        path.add(cp("StructuredSemanticPlan", "StructuredSemanticPlan", "metric",
                "StructuredSemanticPlan.java", 10, "measure", plan.metric()));
        path.add(cp("AnalysisPlan", "SemanticPlanToAnalysisPlanAdapter", "toAnalysisPlan",
                "SemanticPlanToAnalysisPlanAdapter.java", 87, "measure", analysisPlan.primaryMetric()));
        path.add(cp("GeneratedSql", templateClassForIntent(analysisPlan.intent()), "render",
                templateSourceFile(analysisPlan.intent()), templateRenderLine(analysisPlan.intent()),
                "measure", SqlInspector.parse(generatedSql).metricColumn()));
        return firstMutation("measure", mutation.expected(), mutation.actual(), path);
    }

    private FieldTrace traceTimeGrain(
            StructuredSemanticPlan plan,
            AnalysisPlan analysisPlan,
            String generatedSql,
            SqlFidelityReport.Mutation mutation
    ) {
        List<PipelineCheckpoint> path = new ArrayList<>();
        StructuredPlanProjection projection = analysisPlan.structuredProjection();
        path.add(cp("StructuredSemanticPlan", "StructuredSemanticPlan", "timeGrain",
                "StructuredSemanticPlan.java", 18, "timeGrain", plan.timeGrain()));
        path.add(cp("StructuredPlanProjection", "SemanticPlanToAnalysisPlanAdapter", "buildProjection",
                "SemanticPlanToAnalysisPlanAdapter.java", 115, "timeGrain",
                projection != null ? projection.timeGrain() : null));
        path.add(cp("GeneratedSql", "DimensionBucketingSql", "timeGrainExpression",
                "DimensionBucketingSql.java", 52, "timeGrain",
                SqlInspector.parse(generatedSql).dateTruncGrain()));
        return firstMutation("timeGrain", mutation.expected(), mutation.actual(), path);
    }

    private FieldTrace firstMutation(
            String field,
            String expected,
            String actual,
            List<PipelineCheckpoint> path
    ) {
        PipelineCheckpoint first = null;
        for (int i = 0; i < path.size(); i++) {
            PipelineCheckpoint checkpoint = path.get(i);
            if (!fieldMatches(field, expected, checkpoint.value())) {
                if (isSupersededCheckpoint(field, path, i, expected)) {
                    continue;
                }
                first = checkpoint;
                break;
            }
        }
        if (first == null && !path.isEmpty()) {
            first = path.get(path.size() - 1);
        } else if (first == null) {
            first = new PipelineCheckpoint(
                    "GeneratedSql", "Unknown", "render", "unknown", 0, field, actual);
        }
        return new FieldTrace(field, expected, actual, first, List.copyOf(path));
    }

    private boolean isSupersededCheckpoint(
            String field,
            List<PipelineCheckpoint> path,
            int index,
            String expected
    ) {
        if (!"aggregation".equals(field) || index + 1 >= path.size()) {
            return false;
        }
        PipelineCheckpoint current = path.get(index);
        if (!"IntentAggregationStrategy".equals(current.className())) {
            return false;
        }
        return fieldMatches(field, expected, path.get(index + 1).value());
    }

    private boolean fieldMatches(String field, String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return actual == null || actual.isBlank() || "absent".equalsIgnoreCase(actual);
        }
        if (actual == null || actual.isBlank() || "absent".equalsIgnoreCase(actual)) {
            return false;
        }
        return switch (field) {
            case "relationshipOperands" -> corrEquivalent(expected, actual);
            case "ordering" -> orderEquivalent(expected, actual);
            default -> expected.equalsIgnoreCase(actual)
                    || actual.toUpperCase(Locale.ROOT).contains(expected.toUpperCase(Locale.ROOT));
        };
    }

    private boolean corrEquivalent(String expected, String actual) {
        CorrPair e = parseCorr(expected);
        CorrPair a = parseCorr(actual);
        if (e == null || a == null) {
            return Objects.equals(expected, actual);
        }
        return (eq(e.left(), a.left()) && eq(e.right(), a.right()))
                || (eq(e.left(), a.right()) && eq(e.right(), a.left()));
    }

    private boolean orderEquivalent(String expected, String actual) {
        if (eq(expected, actual)) return true;
        String[] e = expected.split("\\s+");
        String[] a = actual.split("\\s+");
        if (e.length == 0 || a.length == 0) return false;
        return eq(e[0], a[0]);
    }

    private CorrPair parseCorr(String value) {
        if (value == null) return null;
        int start = value.toUpperCase(Locale.ROOT).indexOf("CORR(");
        if (start < 0) return null;
        String inner = value.substring(start + 5, value.length() - 1);
        String[] parts = inner.split(",");
        if (parts.length != 2) return null;
        return new CorrPair(parts[0].trim(), parts[1].trim());
    }

    private AggregationSpec resolveAggregationSpec(TemplateContext ctx) {
        if (ctx.renderHints() != null) {
            AggregationSpec hinted = ctx.renderHints().resolveAggregation(ctx.intent(), ctx.revenueMetric());
            if (hinted != null) {
                return hinted;
            }
        }
        return aggregationStrategy.forSqlIntent(ctx.intent(), ctx.revenueMetric());
    }

    private TemplateContext buildGroupedContext(AnalysisPlan plan, SqlRenderHints hints) {
        String dimension = plan.dimension();
        String grouping = plan.groupingAlias() != null ? plan.groupingAlias() : dimension;
        String bucketExpr = DimensionBucketingSql.resolveBucketExpression(dimension);
        String bucketAlias = grouping != null ? grouping : DimensionBucketingSql.bucketAlias(dimension);
        if (hints != null && hints.timeGrain() != null && dimension != null && !dimension.isBlank()) {
            bucketExpr = DimensionBucketingSql.timeGrainExpression(dimension, hints.timeGrain());
            bucketAlias = DimensionBucketingSql.timeGrainAlias(hints.timeGrain());
        }
        return new TemplateContext(
                plan.question(),
                plan.intent().sqlKind(),
                plan.tableRef(),
                plan.primaryMetric(),
                dimension,
                bucketExpr,
                bucketAlias,
                "primary",
                plan.relationshipVariable(),
                hints);
    }

    private static String plannerAggregation(StructuredSemanticPlan plan) {
        return plan.aggregations() != null ? plan.aggregations().primary() : null;
    }

    private static String templateClassForIntent(AnalysisIntent intent) {
        return switch (intent) {
            case RANKING -> "RankingSqlTemplate";
            case TREND -> "TrendSqlTemplate";
            case DISTRIBUTION -> "DistributionSqlTemplate";
            case COMPARISON -> "ComparisonSqlTemplate";
            case CONTRIBUTION -> "ContributionSqlTemplate";
            case RELATIONSHIP -> "RelationshipSqlTemplate";
            case SCALAR -> "ContributionSqlTemplate";
        };
    }

    private static String templateSourceFile(AnalysisIntent intent) {
        return templateClassForIntent(intent) + ".java";
    }

    private static int templateRenderLine(AnalysisIntent intent) {
        return switch (intent) {
            case RANKING -> 14;
            case RELATIONSHIP -> 11;
            default -> 17;
        };
    }

    private static PipelineCheckpoint cp(
            String stage, String className, String methodName, String sourceFile,
            int lineNumber, String field, String value
    ) {
        return new PipelineCheckpoint(stage, className, methodName, sourceFile, lineNumber, field, value);
    }

    private static String corrLabel(String left, String right) {
        if (left == null && right == null) return null;
        return "CORR(" + left + ", " + right + ")";
    }

    private static String operandLabel(String metric, String relationship, String secondary) {
        return "metric=" + metric + ", relationshipVariable=" + relationship + ", secondaryMetric=" + secondary;
    }

    private static String orderLabel(String column, String direction) {
        if (column == null) return null;
        if (direction == null || direction.isBlank()) return column;
        return column + " " + direction.toUpperCase(Locale.ROOT);
    }

    private static String valueOf(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private record CorrPair(String left, String right) {}
}
