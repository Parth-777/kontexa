package com.example.BACKEND.catalogue.decision.planning;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Domain-aware analytical semantics inferred from dataset context.
 * Supplies revenue columns, efficiency formulas, and intelligent bucketing defaults.
 */
@Component
public class DomainAnalyticalDefaults {

    public record DomainProfile(
            String       domainKey,
            String       revenueColumn,
            String       distanceColumn,
            String       efficiencyFormula,
            double[]     distanceBucketBoundaries,
            boolean      retentionApplicable,
            String       businessRevenueLabel
    ) {}

    private static final Map<String, DomainProfile> PROFILES = Map.of(
            "nyc_taxi", new DomainProfile(
                    "nyc_taxi",
                    "total_amount",
                    "trip_distance",
                    "revenue_per_mile",
                    new double[] { 0, 1, 2, 5, 10, 20, 50 },
                    false,
                    "Revenue"
            )
    );

    public static DomainProfile generic() {
        return new DomainProfile(
                "generic", "total_amount", "distance",
                "value_per_unit", new double[] { 0, 1, 5, 10, 25, 50 },
                true, "Revenue");
    }

    /**
     * Infer domain profile from question text and optional connector/table hints.
     */
    public DomainProfile resolve(String question, Map<String, Object> meta) {
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        String tableHint = metaHint(meta, "table", "dataset", "connector");

        if (isNycTaxi(q, tableHint)) return PROFILES.get("nyc_taxi");
        if (q.contains("taxi") || q.contains("trip distance") || q.contains("fare")) {
            return PROFILES.get("nyc_taxi");
        }
        return generic();
    }

    public String resolveRevenueColumn(DomainProfile profile) {
        return profile != null ? profile.revenueColumn() : "total_amount";
    }

    public double[] distanceBuckets(DomainProfile profile) {
        return profile != null ? profile.distanceBucketBoundaries()
                : generic().distanceBucketBoundaries();
    }

    public boolean retentionSupported(DomainProfile profile, AnalyticalIntentType intent) {
        if (intent != AnalyticalIntentType.RETENTION) return true;
        return profile != null && profile.retentionApplicable();
    }

    private boolean isNycTaxi(String question, String tableHint) {
        String combined = question + " " + (tableHint != null ? tableHint : "");
        return combined.contains("yellow_taxi") || combined.contains("green_taxi")
                || combined.contains("nyc taxi") || combined.contains("tpep")
                || combined.contains("lpep") || combined.contains("trip_distance");
    }

    private String metaHint(Map<String, Object> meta, String... keys) {
        if (meta == null) return null;
        for (String k : keys) {
            Object v = meta.get(k);
            if (v != null) return v.toString().toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
