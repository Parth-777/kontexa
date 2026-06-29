package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;

import java.util.List;

/** Shared fixtures for semantic plan completion tests. */
final class SemanticPlanCompleterTestSupport {

    private SemanticPlanCompleterTestSupport() {}

    static RegistryResolutionBundle nycTaxiBundle() {
        String table = "yellow_taxi_trips";
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("taxi", table, List.of("trip_id"), List.of("nyc_taxi"))),
                List.of(
                        new MetricDescriptor(table + ".total_amount", "total_amount", "FLOAT", "SUM", null),
                        new MetricDescriptor(table + ".Airport_fee", "Airport_fee", "FLOAT", "SUM", null)),
                List.of(new DimensionDescriptor(table + ".airport_flag", "airport_flag", "CATEGORICAL")),
                null);
    }
}
