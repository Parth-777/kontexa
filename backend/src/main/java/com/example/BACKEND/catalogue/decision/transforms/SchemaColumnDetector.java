package com.example.BACKEND.catalogue.decision.transforms;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DatasetProfileRegistry.DatasetProfile;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.HardMetricMappings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Schema-aware column detection from registry bundle and dataset profile.
 */
@Component
public class SchemaColumnDetector {

    private static final List<String> TIMESTAMP_HINTS = List.of(
            "pickup_datetime", "dropoff_datetime", "created_at", "updated_at",
            "event_time", "timestamp", "datetime", "occurred_at", "transaction_date"
    );

    private static final List<String> DISTANCE_HINTS = List.of(
            "trip_distance", "distance", "miles", "mileage"
    );

    public Optional<String> findTimestampColumn(RegistryResolutionBundle bundle, DatasetProfile profile) {
        List<String> candidates = new ArrayList<>();
        if (bundle != null && bundle.dimensions() != null) {
            for (DimensionDescriptor d : bundle.dimensions()) {
                if (isTimestampType(d.type()) || isTimestampName(d.key())) {
                    candidates.add(d.key());
                }
            }
        }
        if (profile != null && profile.primaryTimeDimension() != null) {
            candidates.add(profile.primaryTimeDimension());
        }
        if (bundle != null) {
            TIMESTAMP_HINTS.stream()
                    .filter(h -> columnExistsInRegistry(h, bundle))
                    .forEach(candidates::add);
        }

        return candidates.stream()
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .max(Comparator.comparingInt(this::timestampPriority));
    }

    public List<String> allTimestampCandidates(RegistryResolutionBundle bundle, DatasetProfile profile) {
        List<String> all = new ArrayList<>();
        findTimestampColumn(bundle, profile).ifPresent(all::add);
        if (bundle != null && bundle.dimensions() != null) {
            bundle.dimensions().stream()
                    .filter(d -> isTimestampName(d.key()))
                    .map(DimensionDescriptor::key)
                    .forEach(all::add);
        }
        all.addAll(TIMESTAMP_HINTS.stream()
                .filter(h -> bundle == null || columnExistsInRegistry(h, bundle))
                .toList());
        return all.stream().distinct().toList();
    }

    public Optional<String> resolveNumericColumn(
            String dimensionKey, RegistryResolutionBundle bundle, DatasetProfile profile
    ) {
        if (dimensionKey != null && !dimensionKey.isBlank()
                && !dimensionKey.endsWith("_flag") && !dimensionKey.endsWith("_bucket")) {
            if (bundle != null && bundle.dimensions() != null) {
                boolean inRegistry = bundle.dimensions().stream()
                        .anyMatch(d -> d.key().equalsIgnoreCase(dimensionKey));
                if (inRegistry) return Optional.of(dimensionKey);
            }
            if (isNumericName(dimensionKey)) return Optional.of(dimensionKey);
        }
        if (dimensionKey != null && dimensionKey.toLowerCase(Locale.ROOT).contains("distance")) {
            if (profile != null && profile.primaryDistanceDimension() != null) {
                return Optional.of(profile.primaryDistanceDimension());
            }
            return Optional.of(HardMetricMappings.DISTANCE_DIMENSION);
        }
        return Optional.empty();
    }

    public boolean columnExistsInRegistry(String columnKey, RegistryResolutionBundle bundle) {
        if (columnKey == null || bundle == null) return false;
        if (bundle.dimensions() != null) {
            for (DimensionDescriptor d : bundle.dimensions()) {
                if (d.key().equalsIgnoreCase(columnKey)) return true;
            }
        }
        return false;
    }

    private boolean isTimestampType(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.contains("timestamp") || t.contains("datetime") || t.contains("date");
    }

    private boolean isTimestampName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return TIMESTAMP_HINTS.stream().anyMatch(n::contains);
    }

    private boolean isNumericName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return DISTANCE_HINTS.stream().anyMatch(n::contains)
                || n.contains("amount") || n.contains("fare") || n.contains("tip");
    }

    private int timestampPriority(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("pickup_datetime")) return 100;
        if (n.contains("pickup") && n.contains("datetime")) return 90;
        if (n.contains("datetime")) return 80;
        if (n.contains("created_at")) return 70;
        if (n.contains("timestamp")) return 60;
        return 10;
    }
}
