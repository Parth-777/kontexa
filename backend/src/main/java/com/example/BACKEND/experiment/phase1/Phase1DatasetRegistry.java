package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ObjectiveDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Self-contained dataset registry for Phase-1 benchmarks (10 unrelated schemas).
 */
public final class Phase1DatasetRegistry {

    private Phase1DatasetRegistry() {}

    public record DatasetDef(String id, RegistryResolutionBundle bundle) {}

    private static final Map<String, Supplier<DatasetDef>> DATASETS = Map.ofEntries(
            Map.entry("facility_operations", Phase1DatasetRegistry::facilityOperations),
            Map.entry("subscription_events", Phase1DatasetRegistry::subscriptionEvents),
            Map.entry("weather_observations", Phase1DatasetRegistry::weatherObservations),
            Map.entry("semiconductor_yield", Phase1DatasetRegistry::semiconductorYield),
            Map.entry("hospital_bed_flow", Phase1DatasetRegistry::hospitalBedFlow),
            Map.entry("esports_matches", Phase1DatasetRegistry::esportsMatches),
            Map.entry("satellite_telemetry", Phase1DatasetRegistry::satelliteTelemetry),
            Map.entry("vineyard_production", Phase1DatasetRegistry::vineyardProduction),
            Map.entry("transactions", Phase1DatasetRegistry::transactions),
            Map.entry("orders", Phase1DatasetRegistry::orders),
            Map.entry("oil_operations", Phase1DatasetRegistry::oilOperations),
            Map.entry("student_records", Phase1DatasetRegistry::studentRecords)
    );

    public static List<DatasetDef> all() {
        return DATASETS.values().stream().map(Supplier::get).toList();
    }

    public static DatasetDef get(String id) {
        Supplier<DatasetDef> s = DATASETS.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown dataset: " + id);
        return s.get();
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

    private static DatasetDef transactions() {
        return def("transactions",
                List.of("amount", "quantity"),
                List.of("created_at", "category", "channel", "region"));
    }

    private static DatasetDef orders() {
        return def("orders",
                List.of("total_value", "item_count"),
                List.of("placed_at", "region", "status"));
    }

    private static DatasetDef oilOperations() {
        return def("oil_operations",
                List.of("profit_margin", "total_revenue", "downtime_hours", "maintenance_cost", "carbon_emission"),
                List.of("oil_field", "region", "facility_type", "product_type"));
    }

    private static DatasetDef studentRecords() {
        return def("student_records",
                List.of("exam_score", "attendance_rate", "graduation_rate", "study_hours_per_week",
                        "teacher_experience_years", "class_size"),
                List.of("subject", "school_name", "grade_level"));
    }

    private static DatasetDef def(String table, List<String> metrics, List<String> dimensions) {
        return new DatasetDef(table, bundle(table, metrics, dimensions));
    }

    private static RegistryResolutionBundle bundle(
            String table, List<String> metrics, List<String> dimensions
    ) {
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor(table, table, List.of("id"), List.of("phase1_" + table))),
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
                || lower.contains("shift") || lower.contains("_at");
    }
}
