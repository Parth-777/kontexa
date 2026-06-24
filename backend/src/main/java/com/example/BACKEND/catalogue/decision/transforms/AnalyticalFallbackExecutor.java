package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7 — retries analytical execution with progressive semantic transformations.
 */
@Component
public class AnalyticalFallbackExecutor {

    private final SemanticTransformationEngine transformationEngine;

    public AnalyticalFallbackExecutor(SemanticTransformationEngine transformationEngine) {
        this.transformationEngine = transformationEngine;
    }

    public record FallbackAttempt(
            int attemptIndex,
            String strategy,
            SemanticTransformationResult result
    ) {}

    /**
     * Ordered fallback strategies: bucketization → temporal → top-N → exploratory.
     */
    public List<FallbackAttempt> buildFallbackChain(
            String question,
            String tableRef,
            String metricColumn,
            String dimensionKey,
            RegistryResolutionBundle bundle
    ) {
        List<FallbackAttempt> attempts = new ArrayList<>();

        SemanticTransformationResult primary = transformationEngine.transform(
                question, tableRef, metricColumn, dimensionKey, null,
                AnalyticalIntentKind.DISTRIBUTION, "primary", bundle);
        attempts.add(new FallbackAttempt(0, "primary_derivation", primary));

        SemanticTransformationResult full = transformationEngine.transformWithFallbacks(
                question, tableRef, metricColumn, dimensionKey, null,
                AnalyticalIntentKind.DISTRIBUTION, "fallback", bundle);
        attempts.add(new FallbackAttempt(1, "transformation_fallback_chain", full));

        // Top-N raw grouping attempt
        TemplateContext topN = new TemplateContext(
                question, AnalyticalIntentKind.RANKING, tableRef, metricColumn,
                dimensionKey != null ? dimensionKey : metricColumn,
                "CAST(" + (dimensionKey != null ? dimensionKey : metricColumn) + " AS STRING)",
                "entity", "top_n");
        attempts.add(new FallbackAttempt(2, "top_n_grouping",
                SemanticTransformationResult.ok(
                        new DerivedDimensionSpec(SemanticConcept.TOP_N_SEGMENT, dimensionKey,
                                dimensionKey, topN.bucketExpression(), "entity", true, List.of()),
                        topN, List.of(TransformationStep.of("top_n",
                                "Top-N exploratory grouping",
                                "Grouping by raw dimension limited to top entities")))));

        return attempts;
    }
}
