package com.example.BACKEND.catalogue.decision.synthesis.answer;

import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic warehouse row fixtures aligned with {@link CanonicalQueryModel} shape.
 */
public final class WarehouseRowFixtures {

    private WarehouseRowFixtures() {}

    public static List<Map<String, Object>> rowsFor(CanonicalQueryModel canonical, int caseIndex) {
        if (canonical == null || canonical.measure() == null) {
            return List.of();
        }

        if (canonical.bivariate() != null
                && canonical.bivariate().function() != null
                && (canonical.partition() == null || canonical.partition().column() == null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("correlation_coefficient", round(0.42 + caseIndex * 0.03));
            return List.of(row);
        }

        String metricCol = canonical.measure().column();
        if (canonical.partition() == null || canonical.partition().column() == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(metricCol, round(1_000_000 + caseIndex * 50_000));
            return List.of(row);
        }

        String partitionAlias = partitionAlias(canonical);
        List<String> segments = List.of("North", "South", "East");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(partitionAlias, segments.get(i));
            row.put(metricCol, round(800_000 + caseIndex * 10_000 + i * 125_000));

            if (canonical.bivariate() != null && "CORR".equalsIgnoreCase(canonical.bivariate().function())) {
                row.put("correlation_coefficient", round(0.35 + caseIndex * 0.02 + i * 0.05));
            }
            if (canonical.ratio() != null && canonical.ratio().denominator() != null) {
                row.put(canonical.ratio().denominator().column(),
                        round(200_000 + caseIndex * 5_000 + i * 40_000));
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static String partitionAlias(CanonicalQueryModel canonical) {
        String column = canonical.partition().column();
        String grain = canonical.partition().timeGrain();
        if (grain != null && !grain.isBlank()) {
            return column + "_" + grain.toLowerCase(Locale.ROOT);
        }
        return column;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
