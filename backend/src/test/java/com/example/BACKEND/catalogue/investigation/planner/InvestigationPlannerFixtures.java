package com.example.BACKEND.catalogue.investigation.planner;

import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.SchemaSnapshot;

import java.util.List;

/**
 * Shared catalogue fixture for investigation planner quality tests.
 */
public final class InvestigationPlannerFixtures {

    private InvestigationPlannerFixtures() {}

    public static ApprovedCatalogueSnapshot catalogue() {
        return new ApprovedCatalogueSnapshot(
                "yellow_taxi_trips",
                "yellow_taxi_trips",
                List.of(
                        col("total_amount", "metric", "FLOAT",
                                "Total trip charge including fare, tolls, and surcharges", "SUM"),
                        col("tip_amount", "metric", "FLOAT",
                                "Tip paid by the passenger", "AVG"),
                        col("fare_amount", "metric", "FLOAT",
                                "Base fare before extras", "AVG"),
                        col("trip_distance", "metric", "FLOAT",
                                "Trip distance in miles", "AVG"),
                        col("payment_type", "dimension", "INTEGER",
                                "Payment method code (1=credit, 2=cash, etc.)", "NONE"),
                        col("VendorID", "dimension", "INTEGER",
                                "Taxi vendor identifier", "NONE"),
                        col("PULocationID", "dimension", "INTEGER",
                                "Pickup location zone identifier", "NONE"),
                        col("DOLocationID", "dimension", "INTEGER",
                                "Dropoff location zone identifier", "NONE"),
                        col("region", "dimension", "STRING",
                                "Borough or service region", "NONE"),
                        col("store_and_fwd_flag", "dimension", "STRING",
                                "Whether trip data was stored and forwarded", "NONE"),
                        col("airport_fee", "metric", "FLOAT",
                                "Airport surcharge amount; zero when not an airport trip", "SUM"),
                        col("customer_id", "identifier", "STRING",
                                "Unique customer identifier", "NONE"),
                        col("tpep_pickup_datetime", "timestamp", "TIMESTAMP",
                                "Trip pickup timestamp", "NONE"),
                        col("tpep_dropoff_datetime", "timestamp", "TIMESTAMP",
                                "Trip dropoff timestamp", "NONE")
                ));
    }

    public static SchemaSnapshot schema() {
        return new SchemaSnapshot(
                "yellow_taxi_trips",
                List.of(
                        schemaCol("total_amount", "FLOAT"),
                        schemaCol("tip_amount", "FLOAT"),
                        schemaCol("fare_amount", "FLOAT"),
                        schemaCol("trip_distance", "FLOAT"),
                        schemaCol("payment_type", "INTEGER"),
                        schemaCol("VendorID", "INTEGER"),
                        schemaCol("PULocationID", "INTEGER"),
                        schemaCol("DOLocationID", "INTEGER"),
                        schemaCol("region", "STRING"),
                        schemaCol("store_and_fwd_flag", "STRING"),
                        schemaCol("airport_fee", "FLOAT"),
                        schemaCol("customer_id", "STRING"),
                        schemaCol("tpep_pickup_datetime", "TIMESTAMP"),
                        schemaCol("tpep_dropoff_datetime", "TIMESTAMP")
                ));
    }

    private static ApprovedCatalogueSnapshot.CatalogueColumn col(
            String name, String role, String type, String desc, String agg) {
        return new ApprovedCatalogueSnapshot.CatalogueColumn(
                name, role, type, desc, agg, List.of());
    }

    private static SchemaSnapshot.SchemaColumn schemaCol(String name, String type) {
        return new SchemaSnapshot.SchemaColumn(name, type);
    }
}
