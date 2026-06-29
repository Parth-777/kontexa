package com.example.BACKEND.catalogue.semantic.phase2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live planner routing regression: verifies the GPT semantic planner classifies each
 * question into the correct {@code execution_mode}.
 *
 * <p>This test issues real OpenAI requests, so it is skipped automatically when no usable
 * API key is configured (environment variable {@code OPENAI_API_KEY}, system property
 * {@code openai.api.key}, or the classpath {@code application.properties}). It never fails
 * CI environments that lack credentials.
 */
class ExecutionModeRoutingLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Phase-1 questions route CANONICAL, Phase-2 questions route INVESTIGATION")
    void classifiesExecutionModePerQuestion() {
        String apiKey = resolveProperty("OPENAI_API_KEY", "openai.api.key");
        String model = resolveProperty("OPENAI_MODEL", "openai.model");
        assumeTrue(isUsableKey(apiKey), "No usable OpenAI API key configured — skipping live routing test");

        GptStructuredCompletionClient client = new GptStructuredCompletionClient(apiKey, model, MAPPER);
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(client, MAPPER);

        ApprovedCatalogueSnapshot catalogue = catalogue();
        SchemaSnapshot schema = schema();

        assertAll(
                // --- Phase 1 (canonical) ---
                () -> assertMode(planner, catalogue, schema,
                        "How much revenue came from airport rides?",
                        StructuredSemanticPlan.MODE_CANONICAL),
                () -> assertMode(planner, catalogue, schema,
                        "Show revenue by route",
                        StructuredSemanticPlan.MODE_CANONICAL),
                () -> assertMode(planner, catalogue, schema,
                        "Top 10 routes by revenue",
                        StructuredSemanticPlan.MODE_CANONICAL),
                () -> assertMode(planner, catalogue, schema,
                        "Which routes contribute most to airport revenue?",
                        StructuredSemanticPlan.MODE_CANONICAL),

                // --- Phase 2 (investigation) ---
                () -> assertMode(planner, catalogue, schema,
                        "Why did revenue increase?",
                        StructuredSemanticPlan.MODE_INVESTIGATION),
                () -> assertMode(planner, catalogue, schema,
                        "What caused revenue growth?",
                        StructuredSemanticPlan.MODE_INVESTIGATION),
                () -> assertMode(planner, catalogue, schema,
                        "Which regions drove growth?",
                        StructuredSemanticPlan.MODE_INVESTIGATION),
                () -> assertMode(planner, catalogue, schema,
                        "Why did airport revenue decline?",
                        StructuredSemanticPlan.MODE_INVESTIGATION)
        );
    }

    private static void assertMode(
            GptStructuredSemanticPlanner planner,
            ApprovedCatalogueSnapshot catalogue,
            SchemaSnapshot schema,
            String question,
            String expectedMode
    ) {
        StructuredSemanticPlan plan = planner.plan(question, catalogue, schema);
        assertEquals(expectedMode, plan.executionMode(),
                "Wrong execution_mode for question: \"" + question + "\"");
    }

    private static ApprovedCatalogueSnapshot catalogue() {
        return new ApprovedCatalogueSnapshot(
                "nyc_taxi.rides",
                "nyc_taxi.rides",
                List.of(
                        new ApprovedCatalogueSnapshot.CatalogueColumn(
                                "total_revenue", "metric", "FLOAT",
                                "Total revenue earned from a completed ride, including fares and surcharges",
                                "SUM", List.of()),
                        new ApprovedCatalogueSnapshot.CatalogueColumn(
                                "route", "dimension", "STRING",
                                "Ride route between a pickup and dropoff location",
                                "NONE", List.of("JFK-Manhattan", "LGA-Brooklyn", "Manhattan-Queens")),
                        new ApprovedCatalogueSnapshot.CatalogueColumn(
                                "region", "dimension", "STRING",
                                "Geographic region in which the ride took place",
                                "NONE", List.of("Manhattan", "Brooklyn", "Queens")),
                        new ApprovedCatalogueSnapshot.CatalogueColumn(
                                "airport_flag", "dimension", "BOOLEAN",
                                "Whether the ride involved an airport pickup or dropoff",
                                "NONE", List.of("true", "false")),
                        new ApprovedCatalogueSnapshot.CatalogueColumn(
                                "trip_date", "timestamp", "DATE",
                                "Calendar date on which the ride occurred",
                                "NONE", List.of())
                ));
    }

    private static SchemaSnapshot schema() {
        return new SchemaSnapshot(
                "nyc_taxi.rides",
                List.of(
                        new SchemaSnapshot.SchemaColumn("total_revenue", "FLOAT"),
                        new SchemaSnapshot.SchemaColumn("route", "STRING"),
                        new SchemaSnapshot.SchemaColumn("region", "STRING"),
                        new SchemaSnapshot.SchemaColumn("airport_flag", "BOOLEAN"),
                        new SchemaSnapshot.SchemaColumn("trip_date", "DATE")
                ));
    }

    private static String resolveProperty(String envKey, String propKey) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromSysProp = System.getProperty(propKey);
        if (fromSysProp != null && !fromSysProp.isBlank()) {
            return fromSysProp.trim();
        }
        return fromClasspathProperties(propKey);
    }

    private static String fromClasspathProperties(String propKey) {
        try (InputStream in = ExecutionModeRoutingLiveTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty(propKey);
            return value != null ? value.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isUsableKey(String apiKey) {
        return apiKey != null
                && apiKey.startsWith("sk-")
                && apiKey.length() > 20
                && !apiKey.contains("YOUR")
                && !apiKey.contains("placeholder");
    }
}
