package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;

import java.util.List;

/**
 * 50 factual business questions across 10 unrelated datasets.
 * No causal / explanatory phrasing.
 */
public final class Phase1FactualQuestionBank {

    private Phase1FactualQuestionBank() {}

    public static List<Phase1FactualCase> all() {
        return List.of(
                // facility_operations (5)
                c("facility_operations", "Top 5 shift labels ranked by total defect count",
                        "defect_count", "shift_label", List.of(), true),
                c("facility_operations", "Unit cost share grouped by line code",
                        "unit_cost", "line_code", List.of(), true),
                c("facility_operations", "Output volume trend across report weeks",
                        "output_volume", "report_week", List.of(), true),
                c("facility_operations", "Which line code has the highest unit cost?",
                        "unit_cost", "line_code", List.of(), true),
                c("facility_operations", "Defect count where shift label is Night",
                        "defect_count", "shift_label",
                        List.of(new Phase1FilterSpec("shift_label", "=", "Night")), true),

                // subscription_events (5)
                c("subscription_events", "Total payment amount by billing region",
                        "payment_total", "billing_region", List.of(), true),
                c("subscription_events", "Rank plan tiers by cancellation count descending",
                        "cancellation_count", "plan_tier", List.of(), true),
                c("subscription_events", "Active minutes over event hours",
                        "active_minutes", "event_hour", List.of(), true),
                c("subscription_events", "Payment total for plan tier Premium",
                        "payment_total", "plan_tier",
                        List.of(new Phase1FilterSpec("plan_tier", "=", "Premium")), true),
                c("subscription_events", "Cancellation count grouped by plan tier",
                        "cancellation_count", "plan_tier", List.of(), true),

                // weather_observations (5)
                c("weather_observations", "Highest rainfall by station",
                        "rainfall_total", "station_id", List.of(), true),
                c("weather_observations", "Wind speed by climate zone",
                        "wind_speed", "climate_zone", List.of(), true),
                c("weather_observations", "Pressure readings grouped by observation month",
                        "pressure_value", "observation_month", List.of(), true),
                c("weather_observations", "Rainfall where climate zone is Coastal",
                        "rainfall_total", "climate_zone",
                        List.of(new Phase1FilterSpec("climate_zone", "=", "Coastal")), true),
                c("weather_observations", "Top climate zones by wind speed",
                        "wind_speed", "climate_zone", List.of(), true),

                // semiconductor_yield (5)
                c("semiconductor_yield", "Wafer defect rate by fab line",
                        "wafer_defect_rate", "fab_line", List.of(), true),
                c("semiconductor_yield", "Batch throughput ranked by process node",
                        "batch_throughput", "process_node", List.of(), true),
                c("semiconductor_yield", "Lithography yield over production shifts",
                        "lithography_yield", "production_shift", List.of(), true),
                c("semiconductor_yield", "Lowest lithography yield by process node",
                        "lithography_yield", "process_node", List.of(), true),
                c("semiconductor_yield", "Defect rate for fab line A3",
                        "wafer_defect_rate", "fab_line",
                        List.of(new Phase1FilterSpec("fab_line", "=", "A3")), true),

                // hospital_bed_flow (5)
                c("hospital_bed_flow", "Treatment cost total by care unit",
                        "treatment_cost_total", "care_unit", List.of(), true),
                c("hospital_bed_flow", "Readmissions grouped by acuity level",
                        "readmission_count", "acuity_level", List.of(), true),
                c("hospital_bed_flow", "Longest patient stays ranked by care unit",
                        "length_of_stay_hours", "care_unit", List.of(), true),
                c("hospital_bed_flow", "Treatment cost for care unit ICU",
                        "treatment_cost_total", "care_unit",
                        List.of(new Phase1FilterSpec("care_unit", "=", "ICU")), true),
                c("hospital_bed_flow", "Readmission count by admission week",
                        "readmission_count", "admission_week", List.of(), true),

                // esports_matches (5)
                c("esports_matches", "Prize payout by team region",
                        "prize_payout", "team_region", List.of(), true),
                c("esports_matches", "Peak viewers by match hour",
                        "viewer_peak", "match_hour", List.of(), true),
                c("esports_matches", "Top game titles by prize payout",
                        "prize_payout", "game_title", List.of(), true),
                c("esports_matches", "Viewer peak for team region NA",
                        "viewer_peak", "team_region",
                        List.of(new Phase1FilterSpec("team_region", "=", "NA")), true),
                c("esports_matches", "Match duration by game title",
                        "match_duration_min", "game_title", List.of(), true),

                // satellite_telemetry (5)
                c("satellite_telemetry", "Power draw by ground station",
                        "power_draw_watts", "ground_station", List.of(), true),
                c("satellite_telemetry", "Signal strength ranked by spacecraft",
                        "signal_strength_db", "spacecraft_id", List.of(), true),
                c("satellite_telemetry", "Orbit deviation by telemetry month",
                        "orbit_deviation_km", "telemetry_month", List.of(), true),
                c("satellite_telemetry", "Power draw at ground station Denver",
                        "power_draw_watts", "ground_station",
                        List.of(new Phase1FilterSpec("ground_station", "=", "Denver")), true),
                c("satellite_telemetry", "Top 5 spacecraft with weakest signal strength",
                        "signal_strength_db", "spacecraft_id", List.of(), true),

                // vineyard_production (5)
                c("vineyard_production", "Harvest kilograms by grape variety",
                        "harvest_kilograms", "grape_variety", List.of(), true),
                c("vineyard_production", "Sugar brix level by harvest week",
                        "sugar_brix_level", "harvest_week", List.of(), true),
                c("vineyard_production", "Fermentation volume by vineyard block",
                        "fermentation_volume", "vineyard_block", List.of(), true),
                c("vineyard_production", "Harvest kilograms for grape variety Pinot",
                        "harvest_kilograms", "grape_variety",
                        List.of(new Phase1FilterSpec("grape_variety", "=", "Pinot")), true),
                c("vineyard_production", "Top vineyard blocks by fermentation volume",
                        "fermentation_volume", "vineyard_block", List.of(), true),

                // transactions (5)
                c("transactions", "Total transaction amount by category",
                        "amount", "category", List.of(), true),
                c("transactions", "Top regions by transaction amount",
                        "amount", "region", List.of(), true),
                c("transactions", "Quantity sold per channel",
                        "quantity", "channel", List.of(), true),
                c("transactions", "Transaction amount where region is EMEA",
                        "amount", "region",
                        List.of(new Phase1FilterSpec("region", "=", "EMEA")), true),
                c("transactions", "Highest spending category by total amount",
                        "amount", "category", List.of(), true),

                // orders (5)
                c("orders", "Order total value by status",
                        "total_value", "status", List.of(), true),
                c("orders", "Item count by region",
                        "item_count", "region", List.of(), true),
                c("orders", "Order value trend by month from placed_at",
                        "total_value", "placed_at", List.of(), true),
                c("orders", "Orders where status is shipped",
                        "total_value", "status",
                        List.of(new Phase1FilterSpec("status", "=", "shipped")), true),
                c("orders", "Top regions by total order value",
                        "total_value", "region", List.of(), true)
        );
    }

    private static Phase1FactualCase c(
            String dataset, String question, String metric, String dimension,
            List<Phase1FilterSpec> filters, boolean expectSql
    ) {
        return new Phase1FactualCase(dataset, question, metric, dimension, filters, expectSql);
    }
}
