package com.example.BACKEND.catalogue.decision.semantics.catalog;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDrivenQuestionResolverTest {

    private final CatalogQuestionMatcher matcher = new CatalogQuestionMatcher();
    private final SemanticCatalogBuilder catalogBuilder = new SemanticCatalogBuilder();
    private final SchemaDrivenQuestionResolver resolver = new SchemaDrivenQuestionResolver(matcher);

    static Stream<Arguments> oilGasQuestions() {
        return Stream.of(
                Arguments.of(
                        "Which oil field generates the highest profit?",
                        "profit_margin", "oil_field", AnalyticalInvestigationIntent.RANKING),
                Arguments.of(
                        "Which facilities are most efficient?",
                        null, "facility_type", AnalyticalInvestigationIntent.EFFICIENCY),
                Arguments.of(
                        "Which regions are underperforming?",
                        null, "region", AnalyticalInvestigationIntent.RANKING),
                Arguments.of(
                        "Which facility type is most profitable?",
                        "profit_margin", "facility_type", AnalyticalInvestigationIntent.RANKING)
        );
    }

    @ParameterizedTest
    @MethodSource("oilGasQuestions")
    void oilGasQuestions_resolveFromSchemaCatalog(
            String question,
            String expectedMetric,
            String expectedDimension,
            AnalyticalInvestigationIntent expectedIntent
    ) {
        SemanticCatalog catalog = catalogBuilder.build(oilGasBundle());
        SchemaDrivenQuestionResolver.SchemaDrivenResolution resolution =
                resolver.resolve(question, catalog);

        assertTrue(resolution.usable(), "Expected schema resolution for: " + question);
        assertEquals(expectedIntent, resolution.intent());
        if (expectedMetric != null) {
            assertEquals(expectedMetric, resolution.metricColumn());
        }
        assertEquals(expectedDimension, resolution.dimensionColumn());

        assertNotNull(resolution.discovery());
        assertTrue(resolution.discovery().candidateMetrics().contains("profit_margin"));
        assertTrue(resolution.discovery().candidateDimensions().contains("oil_field"));
        assertEquals(expectedDimension, resolution.discovery().dimensionResolution());
    }

    private RegistryResolutionBundle oilGasBundle() {
        String table = "oil_operations";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("oil", table, List.of("well_id"), List.of("oil_gas"))),
                List.of(
                        new MetricDescriptor(table + ".profit_margin", "profit_margin", "FLOAT", "AVG", null),
                        new MetricDescriptor(table + ".total_revenue", "total_revenue", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".maintenance_cost", "maintenance_cost", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".downtime_hours", "downtime_hours", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".oil_field", table + ".oil_field", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".region", table + ".region", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".facility_type", table + ".facility_type", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".product_type", table + ".product_type", "CATEGORICAL")
                ),
                null
        );
    }
}
