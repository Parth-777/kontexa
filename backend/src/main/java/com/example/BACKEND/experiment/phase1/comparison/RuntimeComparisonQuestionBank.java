package com.example.BACKEND.experiment.phase1.comparison;

import java.util.List;

/**
 * 100 human-written business questions for runtime planner comparison.
 *
 * Rules:
 * - Authored manually (not template-generated)
 * - Diverse phrasing and colloquial business language
 * - Never used in regression, validation, Phase-1 factual bank, natural generator, or runtime proof
 */
public final class RuntimeComparisonQuestionBank {

    private RuntimeComparisonQuestionBank() {}

    public static List<RuntimeComparisonQuestion> all() {
        return QUESTIONS;
    }

  /** Hand-authored questions  -  do not auto-generate or reuse benchmark phrasing. */
    private static final List<RuntimeComparisonQuestion> QUESTIONS = List.of(
            // facility_operations (8)
            q("facility_operations", "Which assembly track is burning the most money per widget we ship?"),
            q("facility_operations", "Give me a leaderboard of night crews by how many rejects they logged"),
            q("facility_operations", "How does weekly throughput look when you slice it by production lane?"),
            q("facility_operations", "I need the cheapest line to run - who's winning on per-piece spend?"),
            q("facility_operations", "Break down scrap incidents across the Day versus Weekend rosters"),
            q("facility_operations", "Show output tonnage trending week over week on the factory floor"),
            q("facility_operations", "Which shift crew is quietly racking up quality failures?"),
            q("facility_operations", "Compare per-unit spend across every manufacturing lane we operate"),

            // subscription_events (8)
            q("subscription_events", "Where are churn events clustering geographically for our billing orgs?"),
            q("subscription_events", "Total cash collected broken out by membership package"),
            q("subscription_events", "How engaged are subscribers hour-by-hour during live sessions?"),
            q("subscription_events", "Rank every plan level by how many people bailed this period"),
            q("subscription_events", "Enterprise tier only - what did we actually bill them?"),
            q("subscription_events", "Cancellation volume across each pricing band, highest first"),
            q("subscription_events", "APAC receipts versus minutes people stayed active  -  by the hour"),
            q("subscription_events", "Which billing territory is bleeding the most cancellations?"),

            // weather_observations (8)
            q("weather_observations", "Who got soaked the worst  -  rank monitoring posts by accumulated precipitation"),
            q("weather_observations", "Gust readings across different regional climate buckets"),
            q("weather_observations", "Atmospheric weight trends month by month at our sensor grid"),
            q("weather_observations", "Coastal belt only: how much rain fell at each site?"),
            q("weather_observations", "Top five breeziest climate bands in the network"),
            q("weather_observations", "Barometric heaviness compared across observation months"),
            q("weather_observations", "Which station IDs are flapping in the strongest winds?"),
            q("weather_observations", "Rain totals for every climate classification we track"),

            // semiconductor_yield (8)
            q("semiconductor_yield", "Defect density on each lithography track  -  who's worst?"),
            q("semiconductor_yield", "Wafer batches processed, grouped by nanometer process generation"),
            q("semiconductor_yield", "Yield from exposure steps across factory rotations"),
            q("semiconductor_yield", "Lowest lithography success rate by node size, please"),
            q("semiconductor_yield", "Fab track A3 specifically  -  what's the blemish rate?"),
            q("semiconductor_yield", "Throughput per batch ranked by production shift"),
            q("semiconductor_yield", "Which process node is starving us on pass rate?"),
            q("semiconductor_yield", "Defect counts sliced by semiconductor line identifier"),

            // hospital_bed_flow (8)
            q("hospital_bed_flow", "Billing dollars tied to each inpatient ward"),
            q("hospital_bed_flow", "Return visits within thirty days  -  split by severity tier"),
            q("hospital_bed_flow", "Who keeps patients the longest on the ward?"),
            q("hospital_bed_flow", "ICU spend only  -  total treatment charges"),
            q("hospital_bed_flow", "Readmission tallies week by week as patients arrive"),
            q("hospital_bed_flow", "Length of stay leaders across nursing units"),
            q("hospital_bed_flow", "High-acuity cases: how often do they come back?"),
            q("hospital_bed_flow", "Treatment cost roll-up for every care department"),

            // esports_matches (8)
            q("esports_matches", "Do bigger prize pools correlate with audience peaks?"),
            q("esports_matches", "Eyeballs on stream plotted across match start hours"),
            q("esports_matches", "Prize money share by where the competing teams are based"),
            q("esports_matches", "Longest matches in the league  -  which titles drag on?"),
            q("esports_matches", "Viewer peaks ranked by competitive game franchise"),
            q("esports_matches", "Regional team geography versus tournament purse size"),
            q("esports_matches", "Hourly viewership curve for broadcast events"),
            q("esports_matches", "Which game IP pulled the fattest winner payout?"),

            // satellite_telemetry (8)
            q("satellite_telemetry", "Ground antenna sites  -  power hunger month over month"),
            q("satellite_telemetry", "Link quality versus orbital drift  -  any spacecraft stand out?"),
            q("satellite_telemetry", "Weakest downlink signal among orbiting assets"),
            q("satellite_telemetry", "Electric draw ranked by relay station"),
            q("satellite_telemetry", "Which bird is wandering farthest from its planned path?"),
            q("satellite_telemetry", "Signal strength across telemetry reporting months"),
            q("satellite_telemetry", "Power consumption leaders in the constellation"),
            q("satellite_telemetry", "Deviation from intended orbit  -  worst offenders"),

            // vineyard_production (8)
            q("vineyard_production", "Fermentation tank volume progression harvest week by harvest week"),
            q("vineyard_production", "Berry sugar concentration against tonnage picked  -  how's the relationship?"),
            q("vineyard_production", "Kilograms harvested per vineyard parcel"),
            q("vineyard_production", "Brix levels across grape cultivars"),
            q("vineyard_production", "Which block delivered the heaviest crop?"),
            q("vineyard_production", "Ferment volume by varietal type"),
            q("vineyard_production", "Sugar readings week over week during crush season"),
            q("vineyard_production", "Top parcels by fermentation output"),

            // transactions (9)
            q("transactions", "Spend patterns across merchandise families"),
            q("transactions", "Digital channel revenue versus brick-and-mortar  -  by territory"),
            q("transactions", "Units moved on web purchases only"),
            q("transactions", "Highest grossing product categories this quarter"),
            q("transactions", "Transaction amounts hour-by-hour when they were logged"),
            q("transactions", "EMEA region  -  total purchase value"),
            q("transactions", "Quantity sold grouped by sales channel"),
            q("transactions", "Which category is moving the most units?"),
            q("transactions", "Average ticket size by geographic market"),

            // orders (9)
            q("orders", "Basket value leaders by shipping region"),
            q("orders", "How many line items typically ship per fulfillment status?"),
            q("orders", "Pending orders only  -  what's the dollar total?"),
            q("orders", "Order placement timestamps ranked by cart size"),
            q("orders", "Which status bucket holds the fattest orders?"),
            q("orders", "Item count distribution across delivery zones"),
            q("orders", "Smallest carts  -  when were they placed?"),
            q("orders", "Total order value by current pipeline stage"),
            q("orders", "Regional breakdown of units per shipment"),

            // oil_operations (9)
            q("oil_operations", "Which fields are eating margin alive right now?"),
            q("oil_operations", "Top-line revenue sliced by hydrocarbon product type"),
            q("oil_operations", "Downtime hours burning us across facility categories"),
            q("oil_operations", "Maintenance spend by geographic basin"),
            q("oil_operations", "Carbon output per field  -  who's dirtiest?"),
            q("oil_operations", "Offshore platforms versus onshore pads on profit percentage"),
            q("oil_operations", "Revenue leaders among named extraction sites"),
            q("oil_operations", "Repair bills grouped by asset class"),
            q("oil_operations", "Emissions footprint across operating regions"),

            // student_records (9)
            q("student_records", "Which campuses have kids actually showing up to class?"),
            q("student_records", "Test scores by academic subject  -  who's excelling?"),
            q("student_records", "Do overcrowded rooms hurt exam performance?"),
            q("student_records", "Graduation success rates across grade cohorts"),
            q("student_records", "Study hours per week at schools with veteran instructors"),
            q("student_records", "Attendance percentages ranked by school name"),
            q("student_records", "Which subject lines need the most homework time?"),
            q("student_records", "Teacher tenure versus class headcount  -  any pattern?"),
            q("student_records", "Schools with the strongest diploma completion rates")
    );

    private static RuntimeComparisonQuestion q(String dataset, String question) {
        return new RuntimeComparisonQuestion(dataset, question);
    }
}
