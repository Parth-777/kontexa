package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Dataset profiles with hard metric/dimension bindings for reliable SQL generation.
 */
@Component
public class DatasetProfileRegistry {

    public record DatasetProfile(
            String profileKey,
            String tableRef,
            String primaryRevenueMetric,
            String primaryDistanceDimension,
            String primaryTimeDimension,
            String pickupZoneDimension,
            String weekendDimension
    ) {}

    private static final DatasetProfile NYC_TAXI = new DatasetProfile(
            "nyc_taxi",
            null, // resolved from registry entity
            HardMetricMappings.PRIMARY_REVENUE,
            HardMetricMappings.DISTANCE_DIMENSION,
            HardMetricMappings.TIME_DIMENSION,
            HardMetricMappings.PICKUP_ZONE,
            "weekend_flag"
    );

    private static final Map<String, DatasetProfile> BY_KEY = Map.of(
            "nyc_taxi", NYC_TAXI
    );

    public DatasetProfile resolve(String question, String tableRef) {
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        String table = tableRef != null ? tableRef.toLowerCase(Locale.ROOT) : "";
        boolean nycTaxi = q.contains("taxi") || q.contains("trip distance") || q.contains("fare")
                || q.contains("pickup") || q.contains("yellow")
                || table.contains("yellow_taxi") || table.contains("green_taxi")
                || table.contains("taxi_trips");
        if (nycTaxi) {
            DatasetProfile base = BY_KEY.get("nyc_taxi");
            return new DatasetProfile(
                    base.profileKey(),
                    tableRef,
                    base.primaryRevenueMetric(),
                    base.primaryDistanceDimension(),
                    base.primaryTimeDimension(),
                    base.pickupZoneDimension(),
                    base.weekendDimension());
        }
        return new DatasetProfile("generic", tableRef, null, null, null, null, null);
    }
}
