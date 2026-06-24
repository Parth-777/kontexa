package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalSqlTemplateEngine;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DimensionBucketingSql;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlRenderHints;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationEngine;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates warehouse SQL exclusively from a schema-bound {@link AnalysisPlan}.
 * No question-keyword sniffing or dataset-specific fallbacks.
 */
@Component
public class AnalysisPlanSqlGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPlanSqlGenerator.class);

    private final AnalyticalSqlTemplateEngine templateEngine;
    private final SemanticTransformationEngine transformationEngine;

    public AnalysisPlanSqlGenerator(
            AnalyticalSqlTemplateEngine templateEngine,
            SemanticTransformationEngine transformationEngine
    ) {
        this.templateEngine = templateEngine;
        this.transformationEngine = transformationEngine;
    }

    public List<QuerySpec> generate(AnalysisPlan plan, RegistryResolutionBundle bundle) {
        if (plan == null || !plan.executable()) {
            log.warn("[analysis-plan-sql] blocked: {}",
                    plan != null ? plan.blockingReason() : "null plan");
            return List.of();
        }

        AnalyticalIntentKind sqlIntent = plan.intent().sqlKind();
        return switch (plan.intent()) {
            case RELATIONSHIP -> List.of(buildRelationship(plan));
            case CONTRIBUTION -> plan.dimension() == null || plan.dimension().isBlank()
                    ? List.of(buildScalarShare(plan))
                    : List.of(buildGrouped(plan, sqlIntent, bundle));
            case RANKING, COMPARISON, DISTRIBUTION, TREND ->
                    List.of(buildGrouped(plan, sqlIntent, bundle));
            case SCALAR -> List.of(buildScalarShare(plan));
        };
    }

    private QuerySpec buildScalarShare(AnalysisPlan plan) {
        String part = plan.primaryMetric();
        String total = plan.secondaryMetric();
        String sql = """
                SELECT
                  SUM(%s) AS %s,
                  SUM(%s) AS %s,
                  ROUND(100.0 * SUM(%s) / NULLIF(SUM(%s), 0), 2) AS share_pct
                FROM %s""".formatted(
                part, part, total, total, part, total, plan.tableRef());
        return new QuerySpec(
                "tpl__composition__" + part,
                sql,
                Map.of(
                        "metric", part,
                        "secondary_metric", total,
                        "dimension", "composition",
                        "intent", AnalyticalIntentKind.CONTRIBUTION.name(),
                        "table", plan.tableRef()));
    }

    private QuerySpec buildRelationship(AnalysisPlan plan) {
        TemplateContext ctx = TemplateContext.relationship(
                plan.question(),
                plan.tableRef(),
                plan.primaryMetric(),
                plan.relationshipVariable(),
                "relationship");
        return templateEngine.generate(ctx);
    }

    private QuerySpec buildGrouped(
            AnalysisPlan plan,
            AnalyticalIntentKind intent,
            RegistryResolutionBundle bundle
    ) {
        String dimension = plan.dimension();
        String grouping = plan.groupingAlias() != null ? plan.groupingAlias() : dimension;

        SemanticTransformationResult transform = transformationEngine.transform(
                plan.question(),
                plan.tableRef(),
                plan.primaryMetric(),
                dimension,
                grouping,
                intent,
                "primary",
                bundle);

        if (!transform.success()) {
            transform = transformationEngine.transformWithFallbacks(
                    plan.question(),
                    plan.tableRef(),
                    plan.primaryMetric(),
                    dimension,
                    grouping,
                    intent,
                    "primary",
                    bundle);
        }

        if (transform.success() && transform.templateContext() != null) {
            TemplateContext ctx = withStructuredHints(transform.templateContext(), plan, dimension, grouping);
            return templateEngine.generate(ctx);
        }

        return templateEngine.generate(buildFallbackContext(plan, intent, dimension, grouping));
    }

    private TemplateContext buildFallbackContext(
            AnalysisPlan plan,
            AnalyticalIntentKind intent,
            String dimension,
            String grouping
    ) {
        SqlRenderHints hints = SqlRenderHints.fromAnalysisPlan(plan);
        String bucketExpr = DimensionBucketingSql.resolveBucketExpression(dimension);
        String bucketAlias = grouping != null ? grouping : DimensionBucketingSql.bucketAlias(dimension);
        if (hints != null && hints.timeGrain() != null && dimension != null && !dimension.isBlank()) {
            bucketExpr = DimensionBucketingSql.timeGrainExpression(dimension, hints.timeGrain());
            bucketAlias = DimensionBucketingSql.timeGrainAlias(hints.timeGrain());
            hints = alignOrderColumn(hints, dimension, bucketAlias);
        }
        return new TemplateContext(
                plan.question(),
                intent,
                plan.tableRef(),
                plan.primaryMetric(),
                dimension,
                bucketExpr,
                bucketAlias,
                "primary",
                null,
                hints);
    }

    private TemplateContext withStructuredHints(
            TemplateContext base,
            AnalysisPlan plan,
            String dimension,
            String grouping
    ) {
        SqlRenderHints hints = SqlRenderHints.fromAnalysisPlan(plan);
        if (hints == null) {
            return base;
        }
        String bucketExpr = base.bucketExpression();
        String bucketAlias = base.bucketAlias();
        if (hints.timeGrain() != null && dimension != null && !dimension.isBlank()) {
            bucketExpr = DimensionBucketingSql.timeGrainExpression(dimension, hints.timeGrain());
            bucketAlias = DimensionBucketingSql.timeGrainAlias(hints.timeGrain());
            hints = alignOrderColumn(hints, dimension, bucketAlias);
        }
        return new TemplateContext(
                base.question(),
                base.intent(),
                base.tableRef(),
                base.revenueMetric(),
                dimension,
                bucketExpr,
                bucketAlias != null ? bucketAlias : grouping,
                base.candidateId(),
                base.relationshipVariable(),
                hints);
    }

    private static SqlRenderHints alignOrderColumn(
            SqlRenderHints hints, String dimension, String bucketAlias
    ) {
        if (hints == null || hints.orderColumn() == null || dimension == null) {
            return hints;
        }
        if (hints.orderColumn().equalsIgnoreCase(dimension)) {
            return new SqlRenderHints(
                    hints.primaryAggregation(),
                    bucketAlias,
                    hints.orderDirection(),
                    hints.resultLimit(),
                    hints.timeGrain());
        }
        return hints;
    }

    public List<QuerySpec> generateAll(AnalysisPlan plan, RegistryResolutionBundle bundle) {
        List<QuerySpec> specs = new ArrayList<>(generate(plan, bundle));
        if (specs.isEmpty()) {
            log.warn("[analysis-plan-sql] no SQL for question={} intent={}",
                    plan != null ? plan.question() : null,
                    plan != null ? plan.intent() : null);
        }
        return specs;
    }
}
