package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

/**
 * Inputs for deterministic SQL template rendering.
 */
public record TemplateContext(
        String              question,
        AnalyticalIntentKind intent,
        String              tableRef,
        String              revenueMetric,
        String              dimensionColumn,
        String              bucketExpression,
        String              bucketAlias,
        String              candidateId,
        String              relationshipVariable,
        SqlRenderHints      renderHints
) {
    public TemplateContext(
            String question, AnalyticalIntentKind intent, String tableRef,
            String revenueMetric, String dimensionColumn, String bucketExpression,
            String bucketAlias, String candidateId
    ) {
        this(question, intent, tableRef, revenueMetric, dimensionColumn,
                bucketExpression, bucketAlias, candidateId, null, null);
    }

    public TemplateContext(
            String question, AnalyticalIntentKind intent, String tableRef,
            String revenueMetric, String dimensionColumn, String bucketExpression,
            String bucketAlias, String candidateId,
            String relationshipVariable
    ) {
        this(question, intent, tableRef, revenueMetric, dimensionColumn,
                bucketExpression, bucketAlias, candidateId, relationshipVariable, null);
    }

    public static TemplateContext relationship(
            String question, String table, String targetMetric,
            String sourceVariable, String candidateId
    ) {
        return new TemplateContext(
                question, AnalyticalIntentKind.RELATIONSHIP, table,
                targetMetric, null, null, "relationship", candidateId, sourceVariable);
    }
    public static TemplateContext contribution(
            String question, String table, String revenue, String dimension, String candidateId
    ) {
        String bucketExpr = DimensionBucketingSql.resolveBucketExpression(dimension);
        String alias = DimensionBucketingSql.bucketAlias(dimension);
        return new TemplateContext(
                question, AnalyticalIntentKind.CONTRIBUTION,
                table, revenue, dimension, bucketExpr, alias, candidateId);
    }

    public TemplateContext withIntent(AnalyticalIntentKind intent) {
        if (intent == this.intent) return this;
        return new TemplateContext(
                question, intent, tableRef, revenueMetric, dimensionColumn,
                bucketExpression, bucketAlias, candidateId, relationshipVariable, renderHints);
    }

    public TemplateContext withRenderHints(SqlRenderHints hints) {
        return new TemplateContext(
                question, intent, tableRef, revenueMetric, dimensionColumn,
                bucketExpression, bucketAlias, candidateId, relationshipVariable, hints);
    }

    public static TemplateContext fromQuerySpec(
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec spec,
            String question
    ) {
        var p = spec.params() != null ? spec.params() : java.util.Map.<String, Object>of();
        String table = p.containsKey("table") ? String.valueOf(p.get("table")) : null;
        String metric = p.containsKey("metric") ? String.valueOf(p.get("metric")) : null;
        String dimension = p.containsKey("dimension") ? String.valueOf(p.get("dimension")) : null;
        String intentName = String.valueOf(p.getOrDefault("intent", "CONTRIBUTION"));
        AnalyticalIntentKind intent;
        try {
            intent = AnalyticalIntentKind.valueOf(intentName);
        } catch (IllegalArgumentException ex) {
            intent = AnalyticalIntentKind.CONTRIBUTION;
        }
        String candidateId = spec.key() != null ? spec.key() : "primary";
        return contribution(question, table, metric, dimension, candidateId).withIntent(intent);
    }
}
