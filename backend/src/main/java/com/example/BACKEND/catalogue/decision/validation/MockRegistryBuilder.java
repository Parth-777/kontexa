package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.planning.*;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.ValidationSchemaSpec;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.ValidationSchemaSpec.ColumnSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds mock {@link RegistryResolutionBundle} and {@link InvestigationPlan} objects
 * from a {@link ValidationSchemaSpec} for use in the validation harness.
 *
 * No LLM is called. The plan is built deterministically from the test case's
 * declared intent and dimensional focus.
 */
@Component
public class MockRegistryBuilder {

    public RegistryResolutionBundle buildBundle(ValidationSchemaSpec spec) {
        String table = spec.tableRef();

        EntityDescriptor entity = new EntityDescriptor(
                table, table,
                spec.columns().stream()
                        .filter(c -> !c.isMetric() && !c.isDimension()
                                && c.name().toLowerCase().endsWith("_id"))
                        .map(ColumnSpec::name)
                        .toList(),
                List.of("VALIDATION_MOCK")
        );

        List<MetricDescriptor> metrics = spec.columns().stream()
                .filter(ColumnSpec::isMetric)
                .map(c -> new MetricDescriptor(
                        table + "." + c.name(),
                        c.name(),
                        c.type(),
                        defaultAggregation(c.type()),
                        null
                ))
                .toList();

        List<DimensionDescriptor> dimensions = spec.columns().stream()
                .filter(ColumnSpec::isDimension)
                .map(c -> new DimensionDescriptor(
                        table + "." + c.name(),
                        table + "." + c.name(),
                        toDimensionType(c.type())
                ))
                .toList();

        ObjectiveDescriptor objective = new ObjectiveDescriptor(
                "VALIDATION_OBJECTIVE", "GENERAL_ANALYSIS", List.of());

        return new RegistryResolutionBundle(
                List.of(entity), metrics, dimensions, objective);
    }

    public InvestigationPlan buildPlan(AnalyticalTestCase tc) {
        return new InvestigationPlan(
                "val-" + UUID.randomUUID().toString().substring(0, 8),
                tc.expectedIntent(),
                depthFor(tc.expectedIntent()),
                List.of(new InvestigationStep(1,
                        "VALIDATE_ANALYTICAL_EXECUTION",
                        "Validate that the engine generates correct grouped analytical SQL",
                        List.of(), ComparativeStrategy.ENTITY_DELTA, true)),
                List.of(),
                new ComparativeFramework(
                        List.of(ComparativeStrategy.ENTITY_DELTA),
                        "none", "RANK_BY_METRIC", false, "validation"),
                tc.dimensionalFocus(),
                "Validation harness plan for: " + tc.question()
        );
    }

    private String defaultAggregation(String type) {
        return switch (type.toUpperCase()) {
            case "INT", "BIGINT"               -> "SUM";
            case "FLOAT", "DOUBLE", "DECIMAL"  -> "SUM";
            default                            -> "COUNT";
        };
    }

    private String toDimensionType(String type) {
        return switch (type.toUpperCase()) {
            case "TIMESTAMP", "DATE", "DATETIME" -> "TEMPORAL";
            case "INT", "BIGINT", "FLOAT"        -> "NUMERIC";
            default                              -> "CATEGORICAL";
        };
    }

    private PlanDepth depthFor(com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType intent) {
        return switch (intent) {
            case STRATEGIC_PRIORITIZATION, ROOT_CAUSE_INVESTIGATION, ANOMALY_DETECTION -> PlanDepth.DEEP;
            case RANKING, SEGMENTATION, TREND_ANALYSIS                                 -> PlanDepth.STANDARD;
            default                                                                    -> PlanDepth.MINIMAL;
        };
    }
}
