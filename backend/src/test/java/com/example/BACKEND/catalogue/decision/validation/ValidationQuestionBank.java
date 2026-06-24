package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;

import java.util.ArrayList;
import java.util.List;

/**
 * Fresh validation questions — wording intentionally distinct from
 * {@code SchemaOnlyClientDatasetRegressionTest}.
 */
public final class ValidationQuestionBank {

    private ValidationQuestionBank() {}

    public record ValidationCase(
            String dataset,
            String question,
            AnalysisIntent expectedIntent,
            String expectedMetric,
            String expectedDimension
    ) {}

    public static List<ValidationCase> all() {
        List<ValidationCase> cases = new ArrayList<>();
        cases.addAll(facilityOperations());
        cases.addAll(subscriptionEvents());
        cases.addAll(weatherObservations());
        cases.addAll(semiconductorYield());
        cases.addAll(hospitalBedFlow());
        cases.addAll(esportsMatches());
        cases.addAll(satelliteTelemetry());
        cases.addAll(vineyardProduction());
        return cases;
    }

    private static List<ValidationCase> facilityOperations() {
        String d = "facility_operations";
        return List.of(
                c(d, "Rank shift labels by defect count from highest to lowest", AnalysisIntent.RANKING, "defect_count", "shift_label"),
                c(d, "What is the top line code for output volume?", AnalysisIntent.RANKING, "output_volume", "line_code"),
                c(d, "Break down unit cost share by shift label", AnalysisIntent.CONTRIBUTION, "unit_cost", "shift_label"),
                c(d, "Show how each line code adds to total defect count", AnalysisIntent.CONTRIBUTION, "defect_count", "line_code"),
                c(d, "Does higher output volume drive lower unit cost?", AnalysisIntent.RELATIONSHIP, "unit_cost", null),
                c(d, "Is defect count associated with output volume?", AnalysisIntent.RELATIONSHIP, "defect_count", null),
                c(d, "Spread of defect count across shift labels", AnalysisIntent.DISTRIBUTION, "defect_count", "shift_label"),
                c(d, "Histogram of unit cost by line code", AnalysisIntent.DISTRIBUTION, "unit_cost", "line_code"),
                c(d, "How has unit cost moved across reporting weeks?", AnalysisIntent.TREND, "unit_cost", "report_week"),
                c(d, "Defect count pattern over report week", AnalysisIntent.TREND, "defect_count", "report_week"),
                c(d, "Compare output volume between line codes", AnalysisIntent.COMPARISON, "output_volume", "line_code"),
                c(d, "Side by side unit cost for each shift label", AnalysisIntent.COMPARISON, "unit_cost", "shift_label"),
                c(d, "Which manufacturing shift is most expensive to run?", AnalysisIntent.RANKING, "unit_cost", "shift_label"),
                c(d, "Where do quality issues concentrate on the factory floor?", AnalysisIntent.CONTRIBUTION, "defect_count", "line_code"));
    }

    private static List<ValidationCase> subscriptionEvents() {
        String d = "subscription_events";
        return List.of(
                c(d, "Top billing regions by cancellation count", AnalysisIntent.RANKING, "cancellation_count", "billing_region"),
                c(d, "Which plan tier logs the most active minutes?", AnalysisIntent.RANKING, "active_minutes", "plan_tier"),
                c(d, "Payment total share by billing region", AnalysisIntent.CONTRIBUTION, "payment_total", "billing_region"),
                c(d, "How much does each plan tier add to cancellations?", AnalysisIntent.CONTRIBUTION, "cancellation_count", "plan_tier"),
                c(d, "Do longer active minutes increase payment totals?", AnalysisIntent.RELATIONSHIP, "payment_total", null),
                c(d, "Link between cancellations and active minutes", AnalysisIntent.RELATIONSHIP, "cancellation_count", null),
                c(d, "Distribution of active minutes by plan tier", AnalysisIntent.DISTRIBUTION, "active_minutes", "plan_tier"),
                c(d, "Spread of payment total across billing regions", AnalysisIntent.DISTRIBUTION, "payment_total", "billing_region"),
                c(d, "Payment totals changing across event hours", AnalysisIntent.TREND, "payment_total", "event_hour"),
                c(d, "Cancellation trend by event hour", AnalysisIntent.TREND, "cancellation_count", "event_hour"),
                c(d, "Compare active minutes across plan tiers", AnalysisIntent.COMPARISON, "active_minutes", "plan_tier"),
                c(d, "Billing region comparison for payment total", AnalysisIntent.COMPARISON, "payment_total", "billing_region"),
                c(d, "What region churns subscriptions the most?", AnalysisIntent.RANKING, "cancellation_count", "billing_region"),
                c(d, "Revenue mix across subscription tiers", AnalysisIntent.CONTRIBUTION, "payment_total", "plan_tier"));
    }

    private static List<ValidationCase> weatherObservations() {
        String d = "weather_observations";
        return List.of(
                c(d, "Highest pressure readings by station", AnalysisIntent.RANKING, "pressure_value", "station_id"),
                c(d, "Rank climate zones by wind speed", AnalysisIntent.RANKING, "wind_speed", "climate_zone"),
                c(d, "Rainfall share for each observation month", AnalysisIntent.CONTRIBUTION, "rainfall_total", "observation_month"),
                c(d, "How stations contribute to total wind speed", AnalysisIntent.CONTRIBUTION, "wind_speed", "station_id"),
                c(d, "Does pressure relate to rainfall totals?", AnalysisIntent.RELATIONSHIP, "rainfall_total", null),
                c(d, "Wind speed impact on pressure value", AnalysisIntent.RELATIONSHIP, "pressure_value", null),
                c(d, "Pressure value spread by climate zone", AnalysisIntent.DISTRIBUTION, "pressure_value", "climate_zone"),
                c(d, "Rainfall distribution across stations", AnalysisIntent.DISTRIBUTION, "rainfall_total", "station_id"),
                c(d, "Wind speed over observation months", AnalysisIntent.TREND, "wind_speed", "observation_month"),
                c(d, "Monthly rainfall pattern", AnalysisIntent.TREND, "rainfall_total", "observation_month"),
                c(d, "Compare pressure between climate zones", AnalysisIntent.COMPARISON, "pressure_value", "climate_zone"),
                c(d, "Station level rainfall comparison", AnalysisIntent.COMPARISON, "rainfall_total", "station_id"),
                c(d, "Which station is windiest on average?", AnalysisIntent.RANKING, "wind_speed", "station_id"),
                c(d, "Regional rainfall composition by climate area", AnalysisIntent.CONTRIBUTION, "rainfall_total", "climate_zone"));
    }

    private static List<ValidationCase> semiconductorYield() {
        String d = "semiconductor_yield";
        return List.of(
                c(d, "Best production shift for batch throughput", AnalysisIntent.RANKING, "batch_throughput", "production_shift"),
                c(d, "Top process node by lithography yield", AnalysisIntent.RANKING, "lithography_yield", "process_node"),
                c(d, "Throughput contribution by fab line", AnalysisIntent.CONTRIBUTION, "batch_throughput", "fab_line"),
                c(d, "Defect rate share across process nodes", AnalysisIntent.CONTRIBUTION, "wafer_defect_rate", "process_node"),
                c(d, "Does defect rate hurt lithography yield?", AnalysisIntent.RELATIONSHIP, "lithography_yield", null),
                c(d, "Throughput versus defect rate correlation", AnalysisIntent.RELATIONSHIP, "batch_throughput", null),
                c(d, "Lithography yield spread by fab line", AnalysisIntent.DISTRIBUTION, "lithography_yield", "fab_line"),
                c(d, "Defect rate distribution by production shift", AnalysisIntent.DISTRIBUTION, "wafer_defect_rate", "production_shift"),
                c(d, "Yield movement across production shifts", AnalysisIntent.TREND, "lithography_yield", "production_shift"),
                c(d, "Batch throughput trend by shift", AnalysisIntent.TREND, "batch_throughput", "production_shift"),
                c(d, "Compare defect rate between fab lines", AnalysisIntent.COMPARISON, "wafer_defect_rate", "fab_line"),
                c(d, "Process node throughput comparison", AnalysisIntent.COMPARISON, "batch_throughput", "process_node"),
                c(d, "Which fab line runs the fastest batches?", AnalysisIntent.RANKING, "batch_throughput", "fab_line"),
                c(d, "Yield mix across process nodes", AnalysisIntent.CONTRIBUTION, "lithography_yield", "process_node"));
    }

    private static List<ValidationCase> hospitalBedFlow() {
        String d = "hospital_bed_flow";
        return List.of(
                c(d, "Care units with highest readmission counts", AnalysisIntent.RANKING, "readmission_count", "care_unit"),
                c(d, "Top acuity level by treatment cost total", AnalysisIntent.RANKING, "treatment_cost_total", "acuity_level"),
                c(d, "Readmission share by care unit", AnalysisIntent.CONTRIBUTION, "readmission_count", "care_unit"),
                c(d, "Treatment cost contribution by acuity level", AnalysisIntent.CONTRIBUTION, "treatment_cost_total", "acuity_level"),
                c(d, "Does stay length influence treatment cost?", AnalysisIntent.RELATIONSHIP, "treatment_cost_total", null),
                c(d, "Readmissions versus length of stay", AnalysisIntent.RELATIONSHIP, "readmission_count", null),
                c(d, "Treatment cost spread across care units", AnalysisIntent.DISTRIBUTION, "treatment_cost_total", "care_unit"),
                c(d, "Readmission distribution by acuity level", AnalysisIntent.DISTRIBUTION, "readmission_count", "acuity_level"),
                c(d, "Treatment cost over admission weeks", AnalysisIntent.TREND, "treatment_cost_total", "admission_week"),
                c(d, "Readmission trend across admission weeks", AnalysisIntent.TREND, "readmission_count", "admission_week"),
                c(d, "Compare stay hours between care units", AnalysisIntent.COMPARISON, "length_of_stay_hours", "care_unit"),
                c(d, "Acuity level cost comparison", AnalysisIntent.COMPARISON, "treatment_cost_total", "acuity_level"),
                c(d, "Which ward has the longest stays?", AnalysisIntent.RANKING, "length_of_stay_hours", "care_unit"),
                c(d, "Cost breakdown across hospital wards", AnalysisIntent.CONTRIBUTION, "treatment_cost_total", "care_unit"));
    }

    private static List<ValidationCase> esportsMatches() {
        String d = "esports_matches";
        return List.of(
                c(d, "Longest matches by game title", AnalysisIntent.RANKING, "match_duration_min", "game_title"),
                c(d, "Top team region for viewer peak", AnalysisIntent.RANKING, "viewer_peak", "team_region"),
                c(d, "Prize payout share by game title", AnalysisIntent.CONTRIBUTION, "prize_payout", "game_title"),
                c(d, "Viewer peak contribution by region", AnalysisIntent.CONTRIBUTION, "viewer_peak", "team_region"),
                c(d, "Do longer matches attract more viewers?", AnalysisIntent.RELATIONSHIP, "viewer_peak", null),
                c(d, "Prize payout linked to match duration", AnalysisIntent.RELATIONSHIP, "prize_payout", null),
                c(d, "Viewer peak spread by game title", AnalysisIntent.DISTRIBUTION, "viewer_peak", "game_title"),
                c(d, "Prize payout distribution by team region", AnalysisIntent.DISTRIBUTION, "prize_payout", "team_region"),
                c(d, "Viewer counts across match hours", AnalysisIntent.TREND, "viewer_peak", "match_hour"),
                c(d, "Prize money trend over match hour", AnalysisIntent.TREND, "prize_payout", "match_hour"),
                c(d, "Compare match duration between games", AnalysisIntent.COMPARISON, "match_duration_min", "game_title"),
                c(d, "Regional viewer peak comparison", AnalysisIntent.COMPARISON, "viewer_peak", "team_region"),
                c(d, "Which title pays the most prize money?", AnalysisIntent.RANKING, "prize_payout", "game_title"),
                c(d, "Audience share by competitive region", AnalysisIntent.CONTRIBUTION, "viewer_peak", "team_region"));
    }

    private static List<ValidationCase> satelliteTelemetry() {
        String d = "satellite_telemetry";
        return List.of(
                c(d, "Spacecraft with strongest signal readings", AnalysisIntent.RANKING, "signal_strength_db", "spacecraft_id"),
                c(d, "Top ground station for power draw", AnalysisIntent.RANKING, "power_draw_watts", "ground_station"),
                c(d, "Power draw share by ground station", AnalysisIntent.CONTRIBUTION, "power_draw_watts", "ground_station"),
                c(d, "Orbit deviation contribution by spacecraft", AnalysisIntent.CONTRIBUTION, "orbit_deviation_km", "spacecraft_id"),
                c(d, "Does orbit deviation affect signal strength?", AnalysisIntent.RELATIONSHIP, "signal_strength_db", null),
                c(d, "Power draw versus orbit deviation", AnalysisIntent.RELATIONSHIP, "power_draw_watts", null),
                c(d, "Signal strength spread by ground station", AnalysisIntent.DISTRIBUTION, "signal_strength_db", "ground_station"),
                c(d, "Orbit deviation distribution by spacecraft", AnalysisIntent.DISTRIBUTION, "orbit_deviation_km", "spacecraft_id"),
                c(d, "Signal strength across telemetry months", AnalysisIntent.TREND, "signal_strength_db", "telemetry_month"),
                c(d, "Power draw trend by telemetry month", AnalysisIntent.TREND, "power_draw_watts", "telemetry_month"),
                c(d, "Compare orbit deviation between spacecraft", AnalysisIntent.COMPARISON, "orbit_deviation_km", "spacecraft_id"),
                c(d, "Ground station signal comparison", AnalysisIntent.COMPARISON, "signal_strength_db", "ground_station"),
                c(d, "Which satellite drifts most from orbit?", AnalysisIntent.RANKING, "orbit_deviation_km", "spacecraft_id"),
                c(d, "Energy usage mix across ground stations", AnalysisIntent.CONTRIBUTION, "power_draw_watts", "ground_station"));
    }

    private static List<ValidationCase> vineyardProduction() {
        String d = "vineyard_production";
        return List.of(
                c(d, "Blocks with highest sugar brix level", AnalysisIntent.RANKING, "sugar_brix_level", "vineyard_block"),
                c(d, "Top grape variety by fermentation volume", AnalysisIntent.RANKING, "fermentation_volume", "grape_variety"),
                c(d, "Harvest kilograms share by grape variety", AnalysisIntent.CONTRIBUTION, "harvest_kilograms", "grape_variety"),
                c(d, "Fermentation volume contribution by block", AnalysisIntent.CONTRIBUTION, "fermentation_volume", "vineyard_block"),
                c(d, "Does brix level affect fermentation volume?", AnalysisIntent.RELATIONSHIP, "fermentation_volume", null),
                c(d, "Harvest weight versus sugar brix", AnalysisIntent.RELATIONSHIP, "harvest_kilograms", null),
                c(d, "Brix level spread across vineyard blocks", AnalysisIntent.DISTRIBUTION, "sugar_brix_level", "vineyard_block"),
                c(d, "Harvest kilogram distribution by variety", AnalysisIntent.DISTRIBUTION, "harvest_kilograms", "grape_variety"),
                c(d, "Fermentation volume over harvest weeks", AnalysisIntent.TREND, "fermentation_volume", "harvest_week"),
                c(d, "Sugar brix trend across harvest weeks", AnalysisIntent.TREND, "sugar_brix_level", "harvest_week"),
                c(d, "Compare harvest weight between blocks", AnalysisIntent.COMPARISON, "harvest_kilograms", "vineyard_block"),
                c(d, "Variety level fermentation comparison", AnalysisIntent.COMPARISON, "fermentation_volume", "grape_variety"),
                c(d, "Which block produces the heaviest harvest?", AnalysisIntent.RANKING, "harvest_kilograms", "vineyard_block"),
                c(d, "Wine volume mix by grape type", AnalysisIntent.CONTRIBUTION, "fermentation_volume", "grape_variety"));
    }

    private static ValidationCase c(
            String dataset, String question, AnalysisIntent intent,
            String metric, String dimension
    ) {
        return new ValidationCase(dataset, question, intent, metric, dimension);
    }
}
