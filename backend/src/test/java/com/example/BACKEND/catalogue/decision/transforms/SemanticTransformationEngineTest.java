package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DatasetProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticTransformationEngineTest {

    private SemanticTransformationEngine engine;

    @BeforeEach
    void setUp() {
        var dialect = new BigQueryDialect();
        var schema = new SchemaColumnDetector();
        var temporal = new TemporalDerivationEngine(dialect);
        var bucket = new BucketizationEngine();
        var registry = new DerivedDimensionRegistry();
        engine = new SemanticTransformationEngine(
                registry, schema, temporal, bucket, new DatasetProfileRegistry());
    }

    @Test
    void weekendRides_derivesFromPickupDatetime() {
        SemanticTransformationResult r = engine.transform(
                "How do weekend rides contribute to revenue?",
                "yellow_taxi_trips", "total_amount", "weekend_flag", "weekend_flag",
                AnalyticalIntentKind.DISTRIBUTION, "weekend", emptyBundle());

        assertTrue(r.success());
        assertNotNull(r.templateContext());
        assertTrue(r.templateContext().bucketExpression().contains("DAYOFWEEK"));
        assertTrue(r.templateContext().bucketExpression().contains("Weekend"));
        assertEquals("weekend_flag", r.templateContext().bucketAlias());
        assertFalse(r.traceSteps().isEmpty());
    }

    @Test
    void tripDistance_generatesBucketCaseExpression() {
        SemanticTransformationResult r = engine.transform(
                "How does trip distance affect revenue?",
                "yellow_taxi_trips", "total_amount", "trip_distance", "trip_distance_bucket",
                AnalyticalIntentKind.DISTRIBUTION, "distance", emptyBundle());

        assertTrue(r.success());
        assertTrue(r.templateContext().bucketExpression().contains("CASE"));
        assertTrue(r.templateContext().bucketExpression().contains("'1-3'"));
        assertEquals("trip_distance_bucket", r.templateContext().bucketAlias());
    }

    @Test
    void hourly_derivesHourFromTimestamp() {
        SemanticTransformationResult r = engine.transform(
                "What is hourly revenue?",
                "yellow_taxi_trips", "total_amount", "pickup_hour", "hour_of_day",
                AnalyticalIntentKind.TREND, "hourly", emptyBundle());

        assertTrue(r.success());
        assertTrue(r.templateContext().bucketExpression().contains("EXTRACT(HOUR"));
    }

    private RegistryResolutionBundle emptyBundle() {
        return new RegistryResolutionBundle(List.of(), List.of(), List.of(), null);
    }
}
