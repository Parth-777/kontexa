package com.example.BACKEND.catalogue.decision.execution;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Filters entities to retain only those with statistically meaningful data.
 *
 * The system must not rank a route that appears in 2 trips the same way
 * as a route that appears in 2,000 trips. Low-sample entities produce
 * unreliable metrics that, if ranked, create misleading findings.
 *
 * Filters applied:
 *   1. Minimum sample size  — entities with fewer rows are excluded
 *   2. Outlier suppression  — extreme outliers (>3σ) are excluded from ranking
 *                             (they may still appear as anomaly findings)
 *   3. Variance guard       — single-sample entities with max variance are excluded
 *
 * Also produces a {@link SignificanceReport} so the synthesis prompt knows
 * what was filtered and why.
 */
@Component
public class StatisticalSignificanceGuard {

    private static final int    MIN_SAMPLE_ABSOLUTE   = 3;
    private static final double MIN_SAMPLE_FRACTION   = 0.01; // at least 1% of max sample size
    private static final double OUTLIER_SIGMA         = 3.0;

    public record SignificanceReport(
            int    retained,
            int    filteredLowSample,
            int    filteredOutliers,
            int    minimumSampleUsed,
            String note
    ) {}

    public record FilteredEntities(
            List<ConstructedEntity> retained,
            List<ConstructedEntity> outliers,
            SignificanceReport      report
    ) {}

    public FilteredEntities filter(List<ConstructedEntity> entities) {
        if (entities.isEmpty()) {
            return new FilteredEntities(List.of(), List.of(),
                    new SignificanceReport(0, 0, 0, MIN_SAMPLE_ABSOLUTE, "No entities to filter."));
        }

        long maxSample = entities.stream()
                .mapToLong(ConstructedEntity::sampleSize).max().orElse(1);
        int minSample = Math.max(MIN_SAMPLE_ABSOLUTE,
                (int) Math.ceil(maxSample * MIN_SAMPLE_FRACTION));

        // Step 1: filter low-sample entities
        List<ConstructedEntity> passSample = entities.stream()
                .filter(e -> e.sampleSize() >= minSample)
                .collect(Collectors.toList());
        int filteredSample = entities.size() - passSample.size();

        if (passSample.isEmpty()) {
            // Relax to absolute minimum if nothing passes
            passSample = entities.stream()
                    .filter(e -> e.sampleSize() >= MIN_SAMPLE_ABSOLUTE)
                    .collect(Collectors.toList());
        }

        // Step 2: outlier suppression on primary value metric
        String primaryKey = primaryMetricKey(passSample);
        List<ConstructedEntity> retained = new ArrayList<>();
        List<ConstructedEntity> outliers = new ArrayList<>();

        if (primaryKey != null) {
            List<Double> vals = passSample.stream()
                    .map(e -> e.metrics().getOrDefault(primaryKey, Double.NaN))
                    .filter(v -> !Double.isNaN(v))
                    .collect(Collectors.toList());
            double mean = RowAnalytics.mean(vals);
            double sd   = RowAnalytics.stdDev(vals);

            for (ConstructedEntity e : passSample) {
                double v = e.metrics().getOrDefault(primaryKey, mean);
                if (sd > 0 && Math.abs(v - mean) > OUTLIER_SIGMA * sd) {
                    outliers.add(e);
                } else {
                    retained.add(e);
                }
            }
        } else {
            retained = passSample;
        }

        SignificanceReport report = new SignificanceReport(
                retained.size(),
                filteredSample,
                outliers.size(),
                minSample,
                buildNote(retained.size(), filteredSample, outliers.size(), minSample)
        );

        return new FilteredEntities(retained, outliers, report);
    }

    /**
     * Selects the primary metric key generically: the column with the highest
     * aggregate mean across all entities, excluding internal meta-keys.
     * No domain-specific column names are used.
     */
    private String primaryMetricKey(List<ConstructedEntity> entities) {
        if (entities.isEmpty()) return null;
        Set<String> metaKeys = Set.of("_sample_count");
        Set<String> allKeys = entities.get(0).metrics().keySet().stream()
                .filter(k -> !metaKeys.contains(k) && !k.startsWith("z_score_")
                             && !k.startsWith("share_pct_") && !k.startsWith("ratio_"))
                .collect(java.util.stream.Collectors.toSet());
        if (allKeys.isEmpty()) return null;
        // Pick the key with the highest mean value (most likely the primary magnitude metric)
        return allKeys.stream()
                .max(Comparator.comparingDouble(k ->
                        entities.stream()
                                .mapToDouble(e -> e.metrics().getOrDefault(k, 0.0))
                                .average().orElse(0)))
                .orElse(null);
    }

    private String buildNote(int retained, int filteredSample, int filteredOutliers, int minSample) {
        if (filteredSample == 0 && filteredOutliers == 0)
            return String.format("All %d entities met significance threshold (min sample: %d).", retained, minSample);
        return String.format(
                "%d entities retained; %d excluded (sample < %d); %d outliers suppressed (>3σ).",
                retained, filteredSample, minSample, filteredOutliers);
    }
}
