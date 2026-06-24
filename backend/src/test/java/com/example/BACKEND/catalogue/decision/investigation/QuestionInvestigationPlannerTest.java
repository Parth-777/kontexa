package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.semantic.QueryEntityResolver;
import com.example.BACKEND.catalogue.decision.semantic.SemanticDictionary;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.catalog.CatalogQuestionMatcher;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SchemaDrivenQuestionResolver;
import com.example.BACKEND.catalogue.decision.semantics.catalog.SemanticCatalogBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionInvestigationPlannerTest {

    private final QuestionInvestigationPlanner planner = createPlanner();

    static Stream<Arguments> distinctDimensionQuestions() {
        return Stream.of(
                Arguments.of(
                        "How do airport rides contribute to revenue?",
                        "airport_flag", AnalyticalInvestigationIntent.CONTRIBUTION),
                Arguments.of(
                        "How do weekend rides contribute to revenue?",
                        "weekend_flag", AnalyticalInvestigationIntent.CONTRIBUTION),
                Arguments.of(
                        "How does trip distance affect revenue?",
                        "trip_distance", AnalyticalInvestigationIntent.DISTRIBUTION),
                Arguments.of(
                        "Which pickup zones generate the most revenue?",
                        "pickup_zone", AnalyticalInvestigationIntent.RANKING),
                Arguments.of(
                        "Revenue by payment type",
                        "payment_type", AnalyticalInvestigationIntent.CONTRIBUTION),
                Arguments.of(
                        "Revenue by hour",
                        "pickup_hour", AnalyticalInvestigationIntent.TREND)
        );
    }

    @ParameterizedTest
    @MethodSource("distinctDimensionQuestions")
    void eachQuestion_resolvesDistinctDimension(
            String question, String expectedDimension, AnalyticalInvestigationIntent intent
    ) {
        QuestionInvestigation inv = planner.plan(question, taxiBundle());
        assertTrue(inv.executable(), inv.blockingReason());
        assertEquals(expectedDimension, inv.dimension().columnKey());
        assertEquals(intent, inv.extraction().intent());
        assertNotNull(inv.discovery());
    }

    @Test
    void tipContribution_isShareAnalysisWithoutDimensionBucket() {
        QuestionInvestigation inv = planner.plan(
                "How does tip amount contribute to revenue?", taxiBundle());
        assertTrue(inv.executable());
        assertEquals(AnalyticalInvestigationIntent.SHARE_OF_TOTAL, inv.extraction().intent());
        assertEquals("composition", inv.dimension().columnKey());
        assertNotEquals("trip_distance", inv.dimension().columnKey());
    }

    @Test
    void oilFieldProfitQuestion_resolvesFromSchemaCatalog() {
        QuestionInvestigation inv = planner.plan(
                "Which oil field generates the highest profit?", oilGasBundle());
        assertTrue(inv.executable(), inv.blockingReason());
        assertEquals("oil_field", inv.dimension().columnKey());
        assertEquals("profit_margin", inv.extraction().metricKey());
        assertEquals(AnalyticalInvestigationIntent.RANKING, inv.extraction().intent());
        assertEquals("profit_margin", inv.discovery().metricResolution());
        assertEquals("oil_field", inv.discovery().dimensionResolution());
    }

    @Test
    void distinctQuestions_doNotAllResolveToTripDistance() {
        List<String> dimensions = distinctDimensionQuestions()
                .map(args -> planner.plan(args.get()[0].toString(), taxiBundle()).dimension().columnKey())
                .distinct()
                .toList();
        assertTrue(dimensions.size() >= 4, "Dimensions must differ across questions: " + dimensions);
        assertTrue(dimensions.stream().noneMatch("trip_distance_bucket"::equals)
                        || dimensions.contains("trip_distance"),
                "Only distance questions should use trip_distance");
    }

    private static QuestionInvestigationPlanner createPlanner() {
        CatalogQuestionMatcher matcher = new CatalogQuestionMatcher();
        SemanticCatalogBuilder catalogBuilder = new SemanticCatalogBuilder();
        SchemaDrivenQuestionResolver schemaResolver = new SchemaDrivenQuestionResolver(matcher);
        return new QuestionInvestigationPlanner(
                MetricResolutionTestSupport.extractor(),
                new QueryEntityResolver(new SemanticDictionary()),
                new DimensionResolver(matcher),
                new InvestigationStepPlanner(),
                catalogBuilder,
                schemaResolver,
                new com.example.BACKEND.catalogue.decision.semantics.RelationshipIntentDetector());
    }

    private RegistryResolutionBundle taxiBundle() {
        String table = "yellow_taxi_trips";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", table, List.of("trip_id"), List.of("nyc_taxi"))),
                List.of(
                        new MetricDescriptor(table + ".total_amount", "total_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".tip_amount", "tip_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".trip_distance", "trip_distance", "FLOAT", "SUM", null)
                ),
                List.of(
                        new DimensionDescriptor(table + ".airport_flag", table + ".airport_flag", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".weekend_flag", table + ".weekend_flag", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".trip_distance", table + ".trip_distance", "NUMERIC"),
                        new DimensionDescriptor(table + ".pickup_zone", table + ".pickup_zone", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".payment_type", table + ".payment_type", "CATEGORICAL"),
                        new DimensionDescriptor(table + ".pickup_hour", table + ".pickup_hour", "TEMPORAL")
                ),
                null);
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
                null);
    }
}
