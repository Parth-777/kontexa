package com.example.BACKEND.catalogue.decision.execution.semantic;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.execution.semantic.SchemaSemanticResolver.ResolvedSchema;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Semantic Analytical Execution Engine.
 *
 * Sits between ANALYTICAL_PLANNING and WAREHOUSE_COMPUTE in the pipeline.
 *
 * Converts a structured {@link InvestigationPlan} + resolved schema into
 * typed {@link QuerySpec}s that the warehouse executor runs to produce
 * grouped, ranked, pre-computed evidence.
 *
 * Flow:
 *   InvestigationPlan + RegistryResolutionBundle
 *     → SchemaSemanticResolver   (map physical columns to semantic roles)
 *     → AnalyticalQueryDecomposer (build typed decomposition)
 *     → AnalyticalPlanCompiler    (compile decomposition into execution steps)
 *     → AnalyticalSQLGenerator    (generate SQL from steps)
 *     → List<QuerySpec>           (ready for warehouse execution)
 *
 * The output query specs are PREPENDED to the metric-pack specs so that
 * grouped analytical evidence is always available to the materialization
 * and synthesis layers, regardless of what the metric pack generates.
 *
 * Guarantee:
 *   If a timestamp column exists AND a value metric exists,
 *   the engine WILL produce a temporal-bucketed GROUP BY query.
 *   "Hourly breakdown unavailable" cannot occur if the data exists.
 */
@Service
public class SemanticAnalyticalExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(SemanticAnalyticalExecutionEngine.class);

    private final SchemaSemanticResolver   schemaResolver;
    private final AnalyticalQueryDecomposer decomposer;
    private final AnalyticalPlanCompiler    compiler;
    private final AnalyticalSQLGenerator    sqlGenerator;

    public SemanticAnalyticalExecutionEngine(
            SchemaSemanticResolver    schemaResolver,
            AnalyticalQueryDecomposer decomposer,
            AnalyticalPlanCompiler    compiler,
            AnalyticalSQLGenerator    sqlGenerator
    ) {
        this.schemaResolver = schemaResolver;
        this.decomposer     = decomposer;
        this.compiler       = compiler;
        this.sqlGenerator   = sqlGenerator;
    }

    /**
     * Generate analytical query specs for all entities in the registry bundle.
     *
     * @param plan    The structured investigation plan from the planning engine.
     * @param bundle  The resolved registry bundle with entities, metrics, dimensions.
     * @return        List of QuerySpecs to execute — to be prepended to the metric pack plan.
     */
    public List<QuerySpec> generateQuerySpecs(InvestigationPlan plan, RegistryResolutionBundle bundle) {
        List<QuerySpec> allSpecs = new ArrayList<>();

        for (EntityDescriptor entity : bundle.entities()) {
            try {
                List<QuerySpec> entitySpecs = generateForEntity(plan, bundle, entity);
                allSpecs.addAll(entitySpecs);
                log.info("[saee] entity={} generated {} analytical query specs",
                        entity.tableRef(), entitySpecs.size());
            } catch (Exception e) {
                log.warn("[saee] entity={} spec generation failed: {}",
                        entity.tableRef(), e.getMessage());
                // Non-fatal: metric pack fallback will cover this entity
            }
        }

        log.info("[saee] total analytical specs generated: {}", allSpecs.size());
        return allSpecs;
    }

    private List<QuerySpec> generateForEntity(
            InvestigationPlan        plan,
            RegistryResolutionBundle bundle,
            EntityDescriptor         entity
    ) {
        // Step 1: resolve semantic roles for this entity's schema
        ResolvedSchema schema = schemaResolver.resolve(bundle, entity.tableRef());

        if (!schema.has(SchemaSemanticResolver.SemanticRole.VALUE_METRIC)
                && !schema.has(SchemaSemanticResolver.SemanticRole.VOLUME_METRIC)) {
            log.info("[saee] entity={} has no value/volume metrics — skipping", entity.tableRef());
            return List.of();
        }

        log.info("[saee] entity={} schema: timeDims={} valueCols={} volCols={} entityDims={}",
                entity.tableRef(),
                schema.byRole(SchemaSemanticResolver.SemanticRole.TIME_DIMENSION).size(),
                schema.byRole(SchemaSemanticResolver.SemanticRole.VALUE_METRIC).size(),
                schema.byRole(SchemaSemanticResolver.SemanticRole.VOLUME_METRIC).size(),
                schema.byRole(SchemaSemanticResolver.SemanticRole.ENTITY_DIMENSION).size());

        // Step 2: decompose the plan into typed analytical targets
        AnalyticalDecomposition decomposition = decomposer.decompose(plan, schema);

        if (decomposition.groupingDimensions().isEmpty()
                && !decomposition.temporalSpec().hasTemporalData()) {
            log.info("[saee] entity={} no grouping dimensions found — generating summary only",
                    entity.tableRef());
        }

        // Step 3: compile decomposition into execution steps
        AnalyticalExecutionPlan executionPlan = compiler.compile(decomposition);

        log.info("[saee] entity={} compiled {} steps: {}",
                entity.tableRef(),
                executionPlan.steps().size(),
                executionPlan.steps().stream()
                        .map(s -> s.stepType().name())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none"));

        // Step 4: generate SQL from each step
        return sqlGenerator.generate(executionPlan);
    }
}
