package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalSqlTemplateEngine;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan.QueryPlanStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites analytical query plan steps with derived dimensions before SQL generation.
 */
@Component
public class SemanticQueryRewriter {

    private final SemanticTransformationEngine transformationEngine;
    private final AnalyticalSqlTemplateEngine  templateEngine;

    public SemanticQueryRewriter(
            SemanticTransformationEngine transformationEngine,
            AnalyticalSqlTemplateEngine templateEngine
    ) {
        this.transformationEngine = transformationEngine;
        this.templateEngine = templateEngine;
    }

    public record RewrittenQuery(
            QuerySpec querySpec,
            TemplateContext templateContext,
            List<TransformationStep> transformationSteps
    ) {}

    public RewrittenQuery rewrite(
            String question,
            String tableRef,
            QueryPlanStep step,
            RegistryResolutionBundle bundle
    ) {
        if ("composition".equals(step.grouping())) {
            return null;
        }

        SemanticTransformationResult transform = transformationEngine.transform(
                question, tableRef, step.metric(), step.dimension(),
                step.grouping(), step.sqlIntent(), step.key(), bundle);

        if (!transform.success() || transform.templateContext() == null) {
            transform = transformationEngine.transformWithFallbacks(
                    question, tableRef, step.metric(), step.dimension(),
                    step.grouping(), step.sqlIntent(), step.key(), bundle);
        }

        if (!transform.success() || transform.templateContext() == null) {
            return null;
        }

        QuerySpec spec = templateEngine.generate(transform.templateContext());
        Map<String, Object> params = spec.params() != null
                ? new java.util.LinkedHashMap<>(spec.params()) : new java.util.LinkedHashMap<>();
        if (transform.dimension() != null) {
            params.put("derived_dimension", transform.dimension().outputAlias());
            params.put("source_column", transform.dimension().sourceColumn());
            params.put("transformation", transform.dimension().concept().name());
        }
        QuerySpec enriched = new QuerySpec(spec.key(), spec.sql(), params);
        return new RewrittenQuery(enriched, transform.templateContext(), transform.traceSteps());
    }

    public List<TransformationStep> collectTraceSteps(
            String question, String tableRef,
            List<QueryPlanStep> steps, RegistryResolutionBundle bundle
    ) {
        List<TransformationStep> all = new ArrayList<>();
        for (QueryPlanStep step : steps) {
            RewrittenQuery r = rewrite(question, tableRef, step, bundle);
            if (r != null && r.transformationSteps() != null) {
                all.addAll(r.transformationSteps());
            }
        }
        return all;
    }
}
