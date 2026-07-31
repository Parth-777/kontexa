package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.investigation.planner.InvestigationPlannerFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationObjectiveNormalizerTest {

    private final InvestigationObjectiveNormalizer normalizer = new InvestigationObjectiveNormalizer();

    @Test
    void addsContrastColumnsToExcludeAndRemovesFromDimensions() {
        InvestigationObjective raw = new InvestigationObjective(
                new InvestigationObjective.Measure("tip_amount", "AVG", List.of()),
                new InvestigationObjective.Scope(List.of()),
                new InvestigationObjective.Comparison(
                        new InvestigationObjective.Slice(List.of(
                                new InvestigationObjective.Constraint(
                                        new InvestigationObjective.Filter("payment_type", "=", "1"),
                                        null, null, null))),
                        new InvestigationObjective.Slice(List.of(
                                new InvestigationObjective.Constraint(
                                        new InvestigationObjective.Filter("payment_type", "=", "2"),
                                        null, null, null)))),
                new InvestigationObjective.Decomposition(
                        List.of("payment_type", "trip_distance", "tip_amount", "region"),
                        List.of("tip_amount")),
                InvestigationObjective.SALIENCE_FOCAL_HIGHER,
                0.9);

        var result = normalizer.normalize(raw, InvestigationPlannerFixtures.catalogue());
        assertTrue(result.ok());

        InvestigationObjective out = result.objective();
        assertTrue(containsIgnore(out.decomposition().exclude(), "payment_type"));
        assertTrue(containsIgnore(out.decomposition().exclude(), "tip_amount"));
        assertFalse(containsIgnore(out.decomposition().dimensions(), "payment_type"));
        assertFalse(containsIgnore(out.decomposition().dimensions(), "tip_amount"));
        assertTrue(containsIgnore(out.decomposition().dimensions(), "trip_distance"));
        assertTrue(containsIgnore(out.decomposition().dimensions(), "region"));
    }

    @Test
    void rejectsIdenticalSlices() {
        InvestigationObjective.Slice shared = new InvestigationObjective.Slice(List.of(
                new InvestigationObjective.Constraint(
                        new InvestigationObjective.Filter("region", "=", "Manhattan"),
                        null, null, null)));
        InvestigationObjective raw = new InvestigationObjective(
                new InvestigationObjective.Measure("total_amount", "SUM", List.of()),
                new InvestigationObjective.Scope(List.of()),
                new InvestigationObjective.Comparison(shared, shared),
                new InvestigationObjective.Decomposition(List.of("payment_type"), List.of("total_amount")),
                InvestigationObjective.SALIENCE_DIFFERENCE,
                0.8);

        var result = normalizer.normalize(raw, InvestigationPlannerFixtures.catalogue());
        assertFalse(result.ok());
        assertTrue(result.reason().contains("identical"));
    }

    @Test
    void rejectsUnboundColumns() {
        InvestigationObjective raw = new InvestigationObjective(
                new InvestigationObjective.Measure("revenue_xyz", "SUM", List.of()),
                new InvestigationObjective.Scope(List.of()),
                new InvestigationObjective.Comparison(
                        new InvestigationObjective.Slice(List.of(
                                new InvestigationObjective.Constraint(
                                        new InvestigationObjective.Filter("payment_type", "=", "1"),
                                        null, null, null))),
                        new InvestigationObjective.Slice(List.of(
                                new InvestigationObjective.Constraint(null, null, null, true)))),
                new InvestigationObjective.Decomposition(List.of("region"), List.of()),
                InvestigationObjective.SALIENCE_DIFFERENCE,
                0.7);

        var result = normalizer.normalize(raw, InvestigationPlannerFixtures.catalogue());
        assertFalse(result.ok());
        assertTrue(result.reason().contains("unbound"));
        assertTrue(result.reason().contains("revenue_xyz"));
    }

    @Test
    void normalizesTemporalExcludeWithoutPromptEngineering() {
        InvestigationObjective raw = new InvestigationObjective(
                new InvestigationObjective.Measure("total_amount", "SUM", List.of()),
                new InvestigationObjective.Scope(List.of()),
                new InvestigationObjective.Comparison(
                        new InvestigationObjective.Slice(List.of(
                                new InvestigationObjective.Constraint(
                                        null,
                                        new InvestigationObjective.TimeRange(
                                                "tpep_pickup_datetime", "2024-03-01", "2024-04-01"),
                                        null, null))),
                        new InvestigationObjective.Slice(List.of(
                                new InvestigationObjective.Constraint(
                                        null,
                                        new InvestigationObjective.TimeRange(
                                                "tpep_pickup_datetime", "2024-02-01", "2024-03-01"),
                                        null, null)))),
                new InvestigationObjective.Decomposition(
                        List.of("payment_type", "region"),
                        List.of("total_amount")),
                InvestigationObjective.SALIENCE_FOCAL_HIGHER,
                0.92);

        var result = normalizer.normalize(raw, InvestigationPlannerFixtures.catalogue());
        assertTrue(result.ok());
        assertTrue(containsIgnore(result.objective().decomposition().exclude(), "tpep_pickup_datetime"));
        assertEquals("total_amount", result.objective().measure().column());
    }

    private static boolean containsIgnore(List<String> values, String candidate) {
        return values.stream().anyMatch(v -> v.equalsIgnoreCase(candidate));
    }
}
