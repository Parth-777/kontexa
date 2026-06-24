package com.example.BACKEND.experiment.phase1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Approved catalogue descriptions for Phase-1 benchmarks.
 * Business-language definitions only — no aliases, no regex patterns.
 */
public final class Phase1ApprovedCatalogue {

    public record ColumnDef(
            String column,
            String type,
            String description,
            List<String> sampleFilterValues
    ) {}

    private static final Map<String, List<ColumnDef>> BY_DATASET = new LinkedHashMap<>();

    static {
        register("facility_operations", List.of(
                col("unit_cost", "metric",
                        "Manufacturing cost incurred per unit produced on a factory line",
                        List.of()),
                col("output_volume", "metric",
                        "Total production output volume recorded for a manufacturing line and shift",
                        List.of()),
                col("defect_count", "metric",
                        "Number of quality defects detected during production reporting",
                        List.of()),
                col("line_code", "dimension",
                        "Identifier for a specific factory production line",
                        List.of("L1", "L2", "L3")),
                col("shift_label", "dimension",
                        "Named manufacturing shift such as Day, Night, or Weekend",
                        List.of("Day", "Night", "Weekend")),
                col("report_week", "dimension",
                        "Calendar week when the operational report was filed",
                        List.of())
        ));
        register("subscription_events", List.of(
                col("payment_total", "metric",
                        "Total payment amount collected from a subscription billing event",
                        List.of()),
                col("active_minutes", "metric",
                        "Minutes the subscriber was actively engaged during the event window",
                        List.of()),
                col("cancellation_count", "metric",
                        "Count of subscription cancellations recorded in the event",
                        List.of()),
                col("plan_tier", "dimension",
                        "Subscription plan tier such as Basic, Premium, or Enterprise",
                        List.of("Basic", "Premium", "Enterprise")),
                col("billing_region", "dimension",
                        "Geographic billing region for the subscriber account",
                        List.of("NA", "EMEA", "APAC")),
                col("event_hour", "dimension",
                        "Hour of day when the subscription event occurred",
                        List.of())
        ));
        register("weather_observations", List.of(
                col("rainfall_total", "metric",
                        "Total rainfall amount recorded at a weather station",
                        List.of()),
                col("wind_speed", "metric",
                        "Wind speed measurement at the observation site",
                        List.of()),
                col("pressure_value", "metric",
                        "Atmospheric barometric pressure reading at the station",
                        List.of()),
                col("station_id", "dimension",
                        "Identifier for a weather monitoring station",
                        List.of("STN01", "STN02", "STN03")),
                col("climate_zone", "dimension",
                        "Climate classification zone for the observation area",
                        List.of("Coastal", "Inland", "Mountain")),
                col("observation_month", "dimension",
                        "Month when the weather observation was recorded",
                        List.of())
        ));
        register("semiconductor_yield", List.of(
                col("wafer_defect_rate", "metric",
                        "Rate of defects detected on processed semiconductor wafers",
                        List.of()),
                col("batch_throughput", "metric",
                        "Number of wafers processed in a production batch",
                        List.of()),
                col("lithography_yield", "metric",
                        "Yield percentage from lithography processing steps",
                        List.of()),
                col("fab_line", "dimension",
                        "Semiconductor fabrication line identifier",
                        List.of("A3", "B1", "C2")),
                col("process_node", "dimension",
                        "Manufacturing process node size for the wafer batch",
                        List.of("5nm", "7nm", "14nm")),
                col("production_shift", "dimension",
                        "Factory shift during semiconductor production",
                        List.of("Alpha", "Beta", "Gamma"))
        ));
        register("hospital_bed_flow", List.of(
                col("length_of_stay_hours", "metric",
                        "Patient length of stay measured in hours",
                        List.of()),
                col("readmission_count", "metric",
                        "Number of patient readmissions recorded for the encounter",
                        List.of()),
                col("treatment_cost_total", "metric",
                        "Total treatment and billing cost for the patient stay",
                        List.of()),
                col("care_unit", "dimension",
                        "Hospital care unit or department where the patient was treated",
                        List.of("ICU", "ER", "General")),
                col("acuity_level", "dimension",
                        "Clinical acuity severity level assigned to the patient",
                        List.of("Low", "Medium", "High")),
                col("admission_week", "dimension",
                        "Week when the patient was admitted",
                        List.of())
        ));
        register("esports_matches", List.of(
                col("match_duration_min", "metric",
                        "Duration of the esports match in minutes",
                        List.of()),
                col("viewer_peak", "metric",
                        "Peak concurrent broadcast viewership during the match",
                        List.of()),
                col("prize_payout", "metric",
                        "Prize money paid out for the match outcome",
                        List.of()),
                col("game_title", "dimension",
                        "Video game title played in the competitive match",
                        List.of("Valorant", "League", "CS2")),
                col("team_region", "dimension",
                        "Geographic region of the competing team",
                        List.of("NA", "EU", "KR")),
                col("match_hour", "dimension",
                        "Hour of day when the match was broadcast",
                        List.of())
        ));
        register("satellite_telemetry", List.of(
                col("signal_strength_db", "metric",
                        "Average communication signal strength reported by spacecraft telemetry systems",
                        List.of()),
                col("orbit_deviation_km", "metric",
                        "Orbital position deviation from the planned trajectory in kilometers",
                        List.of()),
                col("power_draw_watts", "metric",
                        "Electrical power consumption drawn by the spacecraft subsystem",
                        List.of()),
                col("spacecraft_id", "dimension",
                        "Identifier for an individual spacecraft or satellite",
                        List.of("SC01", "SC02", "SC03")),
                col("ground_station", "dimension",
                        "Ground station relay site receiving the telemetry",
                        List.of("Denver", "Tokyo", "Dubai")),
                col("telemetry_month", "dimension",
                        "Month when telemetry measurements were collected",
                        List.of())
        ));
        register("vineyard_production", List.of(
                col("harvest_kilograms", "metric",
                        "Total grape harvest weight collected in kilograms",
                        List.of()),
                col("sugar_brix_level", "metric",
                        "Sugar concentration measured in degrees Brix during fermentation",
                        List.of()),
                col("fermentation_volume", "metric",
                        "Volume of wine fermenting in production tanks",
                        List.of()),
                col("vineyard_block", "dimension",
                        "Named block or parcel within the vineyard estate",
                        List.of("Block A", "Block B", "Block C")),
                col("grape_variety", "dimension",
                        "Grape varietal grown and harvested",
                        List.of("Pinot", "Chardonnay", "Merlot")),
                col("harvest_week", "dimension",
                        "Week of the harvest season when grapes were picked",
                        List.of())
        ));
        register("transactions", List.of(
                col("amount", "metric",
                        "Monetary amount of a financial transaction",
                        List.of()),
                col("quantity", "metric",
                        "Quantity of items involved in the transaction",
                        List.of()),
                col("created_at", "dimension",
                        "Timestamp when the transaction was created",
                        List.of()),
                col("category", "dimension",
                        "Spending category for the transaction",
                        List.of("Travel", "Software", "Hardware")),
                col("channel", "dimension",
                        "Sales or payment channel used for the transaction",
                        List.of("Web", "Mobile", "POS")),
                col("region", "dimension",
                        "Geographic region where the transaction occurred",
                        List.of("EMEA", "NA", "APAC"))
        ));
        register("orders", List.of(
                col("total_value", "metric",
                        "Total monetary value of a customer order",
                        List.of()),
                col("item_count", "metric",
                        "Number of items included in the order",
                        List.of()),
                col("placed_at", "dimension",
                        "Timestamp when the order was placed",
                        List.of()),
                col("region", "dimension",
                        "Fulfillment region for the order",
                        List.of("West", "East", "Central")),
                col("status", "dimension",
                        "Current fulfillment status of the order",
                        List.of("shipped", "pending", "cancelled"))
        ));
        register("oil_operations", List.of(
                col("profit_margin", "metric",
                        "Profit margin percentage for oil field operations",
                        List.of()),
                col("total_revenue", "metric",
                        "Total revenue generated from oil production",
                        List.of()),
                col("downtime_hours", "metric",
                        "Hours of operational downtime at the facility",
                        List.of()),
                col("maintenance_cost", "metric",
                        "Maintenance expenditure for oil field equipment",
                        List.of()),
                col("carbon_emission", "metric",
                        "Carbon emissions produced by the operation",
                        List.of()),
                col("oil_field", "dimension",
                        "Name of the oil field asset",
                        List.of("North Ridge", "Delta", "Basin 7")),
                col("region", "dimension",
                        "Geographic region of the oil operation",
                        List.of("Gulf", "Permian", "North Sea")),
                col("facility_type", "dimension",
                        "Type of oil production facility",
                        List.of("Offshore", "Onshore", "Refinery")),
                col("product_type", "dimension",
                        "Oil product type produced",
                        List.of("Crude", "Gas", "Condensate"))
        ));
        register("student_records", List.of(
                col("exam_score", "metric",
                        "Student score on standardized examinations",
                        List.of()),
                col("attendance_rate", "metric",
                        "Percentage of classes attended by the student",
                        List.of()),
                col("graduation_rate", "metric",
                        "Graduation rate metric for the student cohort",
                        List.of()),
                col("study_hours_per_week", "metric",
                        "Average study hours per week reported by students",
                        List.of()),
                col("teacher_experience_years", "metric",
                        "Years of teaching experience for the instructor",
                        List.of()),
                col("class_size", "metric",
                        "Number of students in the class section",
                        List.of()),
                col("subject", "dimension",
                        "Academic subject taught in the course",
                        List.of("Math", "Science", "History")),
                col("school_name", "dimension",
                        "Name of the school where the student is enrolled",
                        List.of("Lincoln High", "Riverside", "Oakwood")),
                col("grade_level", "dimension",
                        "Grade level of the student",
                        List.of("9", "10", "11"))
        ));
    }

    private Phase1ApprovedCatalogue() {}

    public static List<ColumnDef> columnsFor(String datasetId) {
        List<ColumnDef> cols = BY_DATASET.get(datasetId);
        if (cols == null) throw new IllegalArgumentException("No approved catalogue for: " + datasetId);
        return cols;
    }

    public static Optional<ColumnDef> column(String datasetId, String columnName) {
        return columnsFor(datasetId).stream()
                .filter(c -> c.column().equalsIgnoreCase(columnName))
                .findFirst();
    }

    public static String queryPhrase(String datasetId, String columnName) {
        return column(datasetId, columnName)
                .map(c -> businessPhrase(c.description()))
                .filter(s -> !s.isBlank())
                .orElse(columnName.replace('_', ' ').toLowerCase(Locale.ROOT));
    }

    /** Short business-language phrase (≤6 words) from an approved description. */
    public static String businessPhrase(String description) {
        if (description == null || description.isBlank()) return "";
        String d = description.trim();
        int cut = d.indexOf(" such as");
        if (cut > 0) d = d.substring(0, cut);
        String[] words = d.split("\\s+");
        int max = Math.min(6, words.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i].toLowerCase(Locale.ROOT));
        }
        String result = sb.toString().replaceAll("\\b(on|a|an|the|by|for|in|at)$", "").trim();
        return result.isBlank() ? d.toLowerCase(Locale.ROOT) : result;
    }

    public static String phraseFromDescription(String description) {
        return businessPhrase(description);
    }

    private static void register(String datasetId, List<ColumnDef> columns) {
        BY_DATASET.put(datasetId, List.copyOf(columns));
    }

    private static ColumnDef col(String column, String type, String description, List<String> samples) {
        return new ColumnDef(column, type, description, samples);
    }
}
