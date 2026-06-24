package com.example.BACKEND.catalogue.semantic.phase2;



import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;



import java.util.List;



import static org.junit.jupiter.api.Assertions.*;



class SemanticPlanValidatorTest {



    private SemanticPlanValidator validator;

    private ApprovedCatalogueSnapshot catalogue;



    @BeforeEach

    void setUp() {

        SemanticPlanningProperties props = new SemanticPlanningProperties();

        props.setMinConfidence(0.4);

        validator = new SemanticPlanValidator(props);

        catalogue = SemanticCatalogueFactory.catalogueFrom(null, MetricResolutionTestSupport.oilBundle());

    }



    @Test

    void acceptsValidRankingPlan() {

        var plan = new StructuredSemanticPlan(

                "RANKING", "profit_margin", null,

                List.of("oil_field"), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("AVG", null),

                null, null, null, null,

                0.9, "rank fields", List.of());



        var result = validator.validate(plan, catalogue);

        assertTrue(result.valid(), result.issues().toString());

    }



    @Test

    void rejectsUnknownMetric() {

        var plan = new StructuredSemanticPlan(

                "RANKING", "unknown_metric", null,

                List.of("oil_field"), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("AVG", null),

                null, null, null, null,

                0.9, "bad metric", List.of());



        var result = validator.validate(plan, catalogue);

        assertFalse(result.valid());

        assertTrue(result.issues().stream().anyMatch(i -> i.startsWith("INVALID_METRIC")));

    }



    @Test

    void requiresRelationshipVariable() {

        var plan = new StructuredSemanticPlan(

                "RELATIONSHIP", "profit_margin", null,

                List.of(), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("AVG", null),

                null, null, null, null,

                0.85, "missing rel var", List.of());



        var result = validator.validate(plan, catalogue);

        assertFalse(result.valid());

        assertTrue(result.issues().stream().anyMatch(i -> i.contains("DUPLICATE_RELATIONSHIP_METRICS")

                || i.contains("RELATIONSHIP")));

    }



    @Test

    void acceptsValidRelationshipPlan() {

        var plan = new StructuredSemanticPlan(

                "RELATIONSHIP", "profit_margin", null,

                List.of(), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("AVG", null),

                null, null, "downtime_hours", null,

                0.88, "correlation", List.of());



        var result = validator.validate(plan, catalogue);

        assertTrue(result.valid(), result.issues().toString());

    }



    @Test

    void acceptsRelationshipWhenSecondaryMetricResolvesDuplicate() {

        var plan = new StructuredSemanticPlan(

                "RELATIONSHIP", "profit_margin", "downtime_hours",

                List.of(), List.of(),

                new StructuredSemanticPlan.SemanticAggregations(null, null),

                null, null, "profit_margin", null,

                0.9, "correlation", List.of());



        var result = validator.validate(plan, catalogue);

        assertTrue(result.valid(), result.issues().toString());

    }



    @Test

    void rejectsDimensionAsRelationshipVariable() {

        var plan = new StructuredSemanticPlan(

                "RELATIONSHIP", "profit_margin", "facility_type",

                List.of("facility_type"), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("AVG", "SUM"),

                null, null, "facility_type", null,

                0.8, "invalid", List.of());



        var result = validator.validate(plan, catalogue);

        assertFalse(result.valid());

        assertTrue(result.issues().stream().anyMatch(i -> i.contains("RELATIONSHIP_VARIABLE_IS_DIMENSION")));

    }



    @Test

    void rejectsLowConfidence() {

        var plan = new StructuredSemanticPlan(

                "DISTRIBUTION", "profit_margin", null,

                List.of("oil_field"), List.of(),

                new StructuredSemanticPlan.SemanticAggregations("AVG", null),

                null, null, null, null,

                0.2, "uncertain", List.of());



        var result = validator.validate(plan, catalogue);

        assertFalse(result.valid());

        assertTrue(result.issues().stream().anyMatch(i -> i.startsWith("LOW_CONFIDENCE")));

    }

}


