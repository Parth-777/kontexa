package com.example.BACKEND.catalogue.decision.analytics;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult.DistributionProfile;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Computes a full distribution profile for the primary value metric.
 *
 * Averages are not insights. This engine exposes:
 *   - Where performance is concentrated (top-10% share)
 *   - Whether distribution is skewed (asymmetric tail)
 *   - Whether a long-tail exists (bottom-50% share)
 *   - Gini-style concentration index
 *
 * These signals surface non-obvious structural facts like:
 *   "Top 10% of zones generate 58% of revenue"
 *   "Revenue is heavily right-skewed — a few dominant entities drive the total"
 */
@Component
public class ComparativeDistributionEngine {

    private static final int MIN_ROWS = 8;

    public DistributionProfile analyse(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < MIN_ROWS) return null;

        List<String> valueCols = RowAnalytics.valueColumns(rows);
        if (valueCols.isEmpty()) return null;

        String metricKey = valueCols.get(0);
        List<Double> vals = RowAnalytics.values(rows, metricKey);
        if (vals.size() < MIN_ROWS) return null;

        double mean        = RowAnalytics.mean(vals);
        double median      = RowAnalytics.median(vals);
        double stdDev      = RowAnalytics.stdDev(vals);
        double skewness    = RowAnalytics.skewness(vals);
        double top10Share  = RowAnalytics.topNSharePercent(vals, 0.10);
        double bot50Share  = RowAnalytics.topNSharePercent(vals, 0.50);
        double gini        = Math.abs(RowAnalytics.concentrationIndex(vals));
        String character   = characterize(gini, skewness, top10Share);

        return new DistributionProfile(
                metricKey, mean, median, stdDev,
                skewness, top10Share, bot50Share,
                gini, character
        );
    }

    private String characterize(double gini, double skewness, double top10Share) {
        if (top10Share >= 60) return "HIGHLY_CONCENTRATED";
        if (top10Share >= 40) return "MODERATELY_CONCENTRATED";
        if (gini > 0.5)       return "SKEWED_DISTRIBUTION";
        if (Math.abs(skewness) > 1.5) return "LONG_TAIL";
        if (gini < 0.2)       return "NORMALLY_DISTRIBUTED";
        return "MODERATE_DISTRIBUTION";
    }
}
