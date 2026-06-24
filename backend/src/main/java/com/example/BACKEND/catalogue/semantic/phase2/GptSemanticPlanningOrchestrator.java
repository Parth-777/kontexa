package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalSqlExecutionService;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.SemanticShadowComparison;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalAnalysisPlanBridge;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidationResult;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidator;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalSqlRenderer;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Production semantic planning: GPT question understanding → {@link CanonicalQueryModel}
 * → validation → {@link CanonicalSqlRenderer} SQL.
 */
@Component
public class GptSemanticPlanningOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GptSemanticPlanningOrchestrator.class);

    private final SemanticPlanningProperties properties;
    private final GptStructuredSemanticPlanner gptPlanner;
    private final SemanticPlanValidator validator;
    private final SemanticPlanToAnalysisPlanAdapter legacyAdapter;
    private final DeterministicAnalyticalQueryPlanner legacySqlPlanner;
    private final AnalyticalSqlExecutionService sqlExecutionService;
    private final CatalogueApprovalService catalogueApprovalService;
    private final GptSemanticShadowLogger shadowLogger;
    private final SemanticShadowComparisonFactory comparisonFactory;
    private final CanonicalQueryModelAdapter canonicalAdapter;
    private final CanonicalQueryValidator canonicalQueryValidator;
    private final CanonicalSqlRenderer canonicalSqlRenderer;
    private final ObjectMapper mapper;

    public GptSemanticPlanningOrchestrator(
            SemanticPlanningProperties properties,
            GptStructuredSemanticPlanner gptPlanner,
            SemanticPlanValidator validator,
            SemanticPlanToAnalysisPlanAdapter legacyAdapter,
            DeterministicAnalyticalQueryPlanner legacySqlPlanner,
            AnalyticalSqlExecutionService sqlExecutionService,
            CatalogueApprovalService catalogueApprovalService,
            GptSemanticShadowLogger shadowLogger,
            SemanticShadowComparisonFactory comparisonFactory,
            CanonicalQueryModelAdapter canonicalAdapter,
            CanonicalQueryValidator canonicalQueryValidator,
            CanonicalSqlRenderer canonicalSqlRenderer,
            ObjectMapper mapper
    ) {
        this.properties = properties;
        this.gptPlanner = gptPlanner;
        this.validator = validator;
        this.legacyAdapter = legacyAdapter;
        this.legacySqlPlanner = legacySqlPlanner;
        this.sqlExecutionService = sqlExecutionService;
        this.catalogueApprovalService = catalogueApprovalService;
        this.shadowLogger = shadowLogger;
        this.comparisonFactory = comparisonFactory;
        this.canonicalAdapter = canonicalAdapter;
        this.canonicalQueryValidator = canonicalQueryValidator;
        this.canonicalSqlRenderer = canonicalSqlRenderer;
        this.mapper = mapper;
    }

    public boolean isLegacyMode() {
        return properties.isLegacy();
    }

    public boolean isShadowMode() {
        return properties.isShadow();
    }

    public boolean isGptMode() {
        return properties.isGpt();
    }

    public GptPlanningOutcome plan(String question, String tenantId, RegistryResolutionBundle bundle) {
        ApprovedCatalogueSnapshot catalogue = loadCatalogue(tenantId, bundle);
        SchemaSnapshot schema = SemanticCatalogueFactory.schemaFrom(bundle);

        StructuredSemanticPlan semanticPlan = gptPlanner.plan(question, catalogue, schema);
        SemanticPlanValidationResult validation = validator.validate(semanticPlan, catalogue);
        log.info("[planner-pipeline-trace] stage=semantic_plan_validation question={} valid={} issues={}",
                question, validation.valid(), validation.issues());
        CanonicalQueryModel canonicalModel = canonicalAdapter.adapt(semanticPlan);
        try {
            log.info("[planner-pipeline-trace] stage=canonical_query_model question={} payload={}",
                    question, mapper.writeValueAsString(canonicalModel));
        } catch (Exception e) {
            log.warn("[planner-pipeline-trace] stage=canonical_query_model serialize failed: {}",
                    e.getMessage());
        }
        CanonicalQueryValidationResult canonicalValidation =
                canonicalQueryValidator.validate(canonicalModel, catalogue);

        List<QuerySpec> specs = List.of();
        AnalysisPlan analysisPlan;

        if (canonicalValidation.valid()) {
            try {
                specs = List.of(canonicalSqlRenderer.render(
                        canonicalModel, catalogue.qualifiedTableName()));
                analysisPlan = CanonicalAnalysisPlanBridge.toAnalysisPlan(
                        question, catalogue.tableRef(), canonicalModel);
                log.info("[canonical] plan executable metric={} partition={} intent={}",
                        canonicalModel.measure() != null ? canonicalModel.measure().column() : null,
                        canonicalModel.partition() != null ? canonicalModel.partition().column() : null,
                        canonicalModel.metadata() != null ? canonicalModel.metadata().intent() : null);
            } catch (Exception e) {
                log.warn("[canonical] SQL render failed: {}", e.getMessage());
                analysisPlan = AnalysisPlan.blocked(question, e.getMessage());
            }
        } else {
            analysisPlan = AnalysisPlan.blocked(
                    question, String.join("; ", canonicalValidation.issues()));
            log.warn("[canonical] validation failed: {}", canonicalValidation.issues());
        }

        return new GptPlanningOutcome(
                semanticPlan, validation, analysisPlan, specs,
                canonicalModel, canonicalValidation);
    }

    /**
     * Shadow mode only: compare legacy template path with canonical plan without serving legacy.
     */
    public SemanticShadowComparison shadowCompare(
            UUID runId,
            String question,
            String tenantId,
            RegistryResolutionBundle bundle,
            AnalysisPlan legacyPlan,
            List<QuerySpec> legacySpecs,
            List<QueryResult> legacyResults
    ) {
        if (!properties.isShadow()) {
            return null;
        }

        GptPlanningOutcome outcome = null;
        StructuredSemanticPlan gptPlan = StructuredSemanticPlan.empty("shadow-error");
        SemanticPlanValidationResult validation = SemanticPlanValidationResult.fail("not-run");
        List<QuerySpec> gptSpecs = List.of();
        List<QueryResult> gptResults = List.of();
        String error = null;

        try {
            outcome = plan(question, tenantId, bundle);
            gptPlan = outcome.semanticPlan();
            validation = outcome.validation();
            gptSpecs = outcome.querySpecs();

            if (properties.isShadowExecuteGptSql() && !gptSpecs.isEmpty()) {
                gptResults = sqlExecutionService.executeTemplateBatch(
                        gptSpecs, question, tenantId, runId);
            }
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("[phase2-shadow] run={} shadow failed: {}", runId, error);
        }

        shadowLogger.log(new GptSemanticShadowLogger.ShadowComparisonEntry(
                runId, question, legacyPlan, gptPlan, validation,
                legacySpecs, gptSpecs, legacyResults, gptResults,
                gptPlan.confidence(), error));

        return comparisonFactory.build(
                properties.getMode().name(),
                question,
                legacyPlan,
                outcome,
                legacySpecs,
                gptSpecs,
                legacyResults,
                gptResults,
                error);
    }

    private ApprovedCatalogueSnapshot loadCatalogue(String tenantId, RegistryResolutionBundle bundle) {
        try {
            String snapshotJson = catalogueApprovalService.getApprovedSnapshot(tenantId);
            JsonNode node = mapper.readTree(snapshotJson);
            return SemanticCatalogueFactory.catalogueFrom(node, bundle);
        } catch (Exception e) {
            log.warn("[phase2] catalogue snapshot unavailable for tenant={}, using bundle fallback: {}",
                    tenantId, e.getMessage());
            return SemanticCatalogueFactory.catalogueFrom(null, bundle);
        }
    }

    public record GptPlanningOutcome(
            StructuredSemanticPlan semanticPlan,
            SemanticPlanValidationResult validation,
            AnalysisPlan analysisPlan,
            List<QuerySpec> querySpecs,
            CanonicalQueryModel canonicalQueryModel,
            CanonicalQueryValidationResult canonicalValidation
    ) {
        public boolean canonicalExecutable() {
            return canonicalValidation != null && canonicalValidation.valid()
                    && querySpecs != null && !querySpecs.isEmpty()
                    && analysisPlan != null && analysisPlan.executable();
        }
    }
}
