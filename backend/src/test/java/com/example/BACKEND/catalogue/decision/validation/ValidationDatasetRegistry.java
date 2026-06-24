package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;

import java.util.List;
import java.util.Locale;

/**
 * Schema-only dataset bundles for validation runs (not used in regression tests).
 */
public final class ValidationDatasetRegistry {

    private ValidationDatasetRegistry() {}

    public record DatasetDef(String name, RegistryResolutionBundle bundle) {}

    public static List<DatasetDef> all() {
        return List.of(
                facilityOperations(),
                subscriptionEvents(),
                weatherObservations(),
                semiconductorYield(),
                hospitalBedFlow(),
                esportsMatches(),
                satelliteTelemetry(),
                vineyardProduction());
    }

    private static DatasetDef facilityOperations() {
        return def("facility_operations",
                List.of("unit_cost", "output_volume", "defect_count"),
                List.of("line_code", "shift_label", "report_week"));
    }

    private static DatasetDef subscriptionEvents() {
        return def("subscription_events",
                List.of("payment_total", "active_minutes", "cancellation_count"),
                List.of("plan_tier", "billing_region", "event_hour"));
    }

    private static DatasetDef weatherObservations() {
        return def("weather_observations",
                List.of("rainfall_total", "wind_speed", "pressure_value"),
                List.of("station_id", "climate_zone", "observation_month"));
    }

    private static DatasetDef semiconductorYield() {
        return def("semiconductor_yield",
                List.of("wafer_defect_rate", "batch_throughput", "lithography_yield"),
                List.of("fab_line", "process_node", "production_shift"));
    }

    private static DatasetDef hospitalBedFlow() {
        return def("hospital_bed_flow",
                List.of("length_of_stay_hours", "readmission_count", "treatment_cost_total"),
                List.of("care_unit", "acuity_level", "admission_week"));
    }

    private static DatasetDef esportsMatches() {
        return def("esports_matches",
                List.of("match_duration_min", "viewer_peak", "prize_payout"),
                List.of("game_title", "team_region", "match_hour"));
    }

    private static DatasetDef satelliteTelemetry() {
        return def("satellite_telemetry",
                List.of("signal_strength_db", "orbit_deviation_km", "power_draw_watts"),
                List.of("spacecraft_id", "ground_station", "telemetry_month"));
    }

    private static DatasetDef vineyardProduction() {
        return def("vineyard_production",
                List.of("harvest_kilograms", "sugar_brix_level", "fermentation_volume"),
                List.of("vineyard_block", "grape_variety", "harvest_week"));
    }

    private static DatasetDef def(String table, List<String> metrics, List<String> dimensions) {
        return new DatasetDef(table, bundle(table, metrics, dimensions));
    }

    private static RegistryResolutionBundle bundle(
            String table, List<String> metrics, List<String> dimensions
    ) {
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor(table, table, List.of("id"), List.of("client_" + table))),
                metrics.stream()
                        .map(m -> new MetricDescriptor(table + "." + m, m, "FLOAT", "SUM", null))
                        .toList(),
                dimensions.stream()
                        .map(d -> new DimensionDescriptor(
                                table + "." + d, d, isTemporal(d) ? "TEMPORAL" : "CATEGORICAL"))
                        .toList(),
                new ObjectiveDescriptor("GENERAL", "ANALYTICAL", List.of()));
    }

    private static boolean isTemporal(String d) {
        String lower = d.toLowerCase(Locale.ROOT);
        return lower.contains("week") || lower.contains("hour") || lower.contains("month")
                || lower.contains("shift");
    }
}
