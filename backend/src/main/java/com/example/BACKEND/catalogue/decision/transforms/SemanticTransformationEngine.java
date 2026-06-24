package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DatasetProfileRegistry;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DatasetProfileRegistry.DatasetProfile;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DimensionBucketingSql;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.TemplateContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Translates business analytical concepts into executable warehouse SQL transformations.
 */
@Component
public class SemanticTransformationEngine {

    private final DerivedDimensionRegistry  registry;
    private final SchemaColumnDetector    schema;
    private final TemporalDerivationEngine temporal;
    private final BucketizationEngine     bucketization;
    private final DatasetProfileRegistry  profileRegistry;

    public SemanticTransformationEngine(
            DerivedDimensionRegistry registry,
            SchemaColumnDetector schema,
            TemporalDerivationEngine temporal,
            BucketizationEngine bucketization,
            DatasetProfileRegistry profileRegistry
    ) {
        this.registry = registry;
        this.schema = schema;
        this.temporal = temporal;
        this.bucketization = bucketization;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Transform a logical dimension into an executable grouping expression.
     */
    public SemanticTransformationResult transform(
            String question,
            String tableRef,
            String metricColumn,
            String dimensionKey,
            String groupingAlias,
            AnalyticalIntentKind sqlIntent,
            String candidateId,
            RegistryResolutionBundle bundle
    ) {
        DatasetProfile profile = profileRegistry.resolve(question, tableRef);
        List<TransformationStep> allSteps = new ArrayList<>();

        Optional<SemanticConcept> concept = registry.resolveConcept(dimensionKey, question);
        if (concept.isPresent() && registry.isTemporal(concept.get())
                && schema.columnExistsInRegistry(dimensionKey, bundle)) {
            return identityTransform(question, tableRef, metricColumn, dimensionKey,
                    groupingAlias, sqlIntent, candidateId, bundle, allSteps);
        }
        if (concept.isEmpty()) {
            return identityTransform(question, tableRef, metricColumn, dimensionKey,
                    groupingAlias, sqlIntent, candidateId, bundle, allSteps);
        }

        SemanticConcept c = concept.get();
        DerivedDimensionSpec derived = deriveConcept(c, dimensionKey, bundle, profile, allSteps);
        if (derived == null || !derived.isExecutable()) {
            return transformWithFallbacks(question, tableRef, metricColumn, dimensionKey,
                    groupingAlias, sqlIntent, candidateId, bundle);
        }

        String alias = groupingAlias != null && !groupingAlias.isBlank()
                ? groupingAlias : derived.outputAlias();
        TemplateContext ctx = new TemplateContext(
                question, sqlIntent, tableRef, metricColumn,
                derived.sourceColumn(), derived.bucketExpression(), alias, candidateId);

        allSteps.addAll(derived.steps());
        return SemanticTransformationResult.ok(derived, ctx, allSteps);
    }

    /**
     * Attempt transformations in order until one produces executable SQL.
     */
    public SemanticTransformationResult transformWithFallbacks(
            String question,
            String tableRef,
            String metricColumn,
            String dimensionKey,
            String groupingAlias,
            AnalyticalIntentKind sqlIntent,
            String candidateId,
            RegistryResolutionBundle bundle
    ) {
        DatasetProfile profile = profileRegistry.resolve(question, tableRef);

        // 1. Primary concept
        Optional<SemanticConcept> primary = registry.resolveConcept(dimensionKey, question);
        if (primary.isPresent()) {
            List<TransformationStep> steps = new ArrayList<>();
            DerivedDimensionSpec d = deriveConcept(primary.get(), dimensionKey, bundle, profile, steps);
            if (d != null && d.isExecutable()) {
                return buildResult(question, tableRef, metricColumn, groupingAlias, sqlIntent, candidateId, d, steps);
            }
        }

        // 2. Alternate timestamp columns — only when question or dimension implies temporal derivation
        Optional<SemanticConcept> temporalNeed = primary.filter(registry::isTemporal)
                .or(() -> registry.fromKey(dimensionKey).filter(registry::isTemporal))
                .or(() -> registry.fromQuestion(question).filter(registry::isTemporal));
        if (temporalNeed.isPresent()) {
            for (String tsCol : schema.allTimestampCandidates(bundle, profile)) {
                List<TransformationStep> steps = new ArrayList<>();
                DerivedDimensionSpec d = temporal.derive(temporalNeed.get(), tsCol, dimensionKey);
                steps.addAll(d.steps());
                if (d.isExecutable()) {
                    return buildResult(question, tableRef, metricColumn, groupingAlias, sqlIntent, candidateId, d, steps);
                }
            }
        }

        // 3. Numeric bucketization — only when concept is numeric
        Optional<SemanticConcept> bucketNeed = primary.filter(registry::isNumericBucket)
                .or(() -> registry.fromKey(dimensionKey).filter(registry::isNumericBucket))
                .or(() -> registry.fromQuestion(question).filter(registry::isNumericBucket));
        if (bucketNeed.isPresent()) {
            Optional<String> numeric = schema.resolveNumericColumn(dimensionKey, bundle, profile);
            if (numeric.isPresent()) {
                DerivedDimensionSpec d = bucketization.bucketize(bucketNeed.get(), numeric.get(), dimensionKey);
                return buildResult(question, tableRef, metricColumn, groupingAlias, sqlIntent, candidateId, d, d.steps());
            }
        }

        // 4. Identity / raw column if in registry
        return identityTransform(question, tableRef, metricColumn, dimensionKey,
                groupingAlias, sqlIntent, candidateId, bundle, new ArrayList<>());
    }

    public List<TransformationStep> stepsForPlan(
            String question, String dimensionKey, RegistryResolutionBundle bundle, String tableRef
    ) {
        SemanticTransformationResult r = transform(
                question, tableRef, "total_amount", dimensionKey, null,
                AnalyticalIntentKind.DISTRIBUTION, "trace", bundle);
        return r.traceSteps() != null ? r.traceSteps() : List.of();
    }

    private DerivedDimensionSpec deriveConcept(
            SemanticConcept concept,
            String dimensionKey,
            RegistryResolutionBundle bundle,
            DatasetProfile profile,
            List<TransformationStep> steps
    ) {
        if (registry.isTemporal(concept)) {
            Optional<String> ts = schema.findTimestampColumn(bundle, profile);
            if (ts.isEmpty()) return null;
            DerivedDimensionSpec d = temporal.derive(concept, ts.get(), dimensionKey);
            steps.addAll(d.steps());
            return d;
        }
        if (registry.isNumericBucket(concept)) {
            Optional<String> num = schema.resolveNumericColumn(dimensionKey, bundle, profile);
            if (num.isEmpty()) return null;
            DerivedDimensionSpec d = bucketization.bucketize(concept, num.get(), dimensionKey);
            steps.addAll(d.steps());
            return d;
        }
        if (concept == SemanticConcept.AIRPORT_RIDE) {
            String expr = DimensionBucketingSql.airportFlag("airport_fee");
            steps.add(TransformationStep.of(
                    "classify_airport_rides",
                    "Classify airport vs non-airport rides",
                    "airport_fee > 0"));
            return new DerivedDimensionSpec(
                    SemanticConcept.AIRPORT_RIDE, "airport_fee", "airport_fee",
                    expr, "airport_flag", true, steps);
        }
        return null;
    }

    private SemanticTransformationResult identityTransform(
            String question, String tableRef, String metricColumn, String dimensionKey,
            String groupingAlias, AnalyticalIntentKind sqlIntent, String candidateId,
            RegistryResolutionBundle bundle, List<TransformationStep> steps
    ) {
        if (dimensionKey == null || dimensionKey.isBlank()) {
            return SemanticTransformationResult.failed("No dimension to transform");
        }

        String alias = groupingAlias != null && !groupingAlias.isBlank()
                ? groupingAlias : dimensionKey;
        String expression = dimensionKey;

        if (!schema.columnExistsInRegistry(dimensionKey, bundle)
                && registry.fromKey(dimensionKey).isEmpty()) {
            return SemanticTransformationResult.failed(
                    "Column " + dimensionKey + " not found and no derivation available");
        }

        steps.add(TransformationStep.of("use_physical_column",
                "Using physical column", "Column: " + dimensionKey));

        DerivedDimensionSpec dim = new DerivedDimensionSpec(
                SemanticConcept.IDENTITY, dimensionKey, dimensionKey, expression, alias, false, steps);
        TemplateContext ctx = new TemplateContext(
                question, sqlIntent, tableRef, metricColumn, dimensionKey, expression, alias, candidateId);
        return SemanticTransformationResult.ok(dim, ctx, steps);
    }

    private SemanticTransformationResult buildResult(
            String question, String tableRef, String metricColumn, String groupingAlias,
            AnalyticalIntentKind sqlIntent, String candidateId,
            DerivedDimensionSpec d, List<TransformationStep> steps
    ) {
        String alias = groupingAlias != null && !groupingAlias.isBlank()
                ? groupingAlias : d.outputAlias();
        TemplateContext ctx = new TemplateContext(
                question, sqlIntent, tableRef, metricColumn,
                d.sourceColumn(), d.bucketExpression(), alias, candidateId);
        return SemanticTransformationResult.ok(d, ctx, steps);
    }
}
