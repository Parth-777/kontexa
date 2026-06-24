package com.example.BACKEND.catalogue.decision.evidence;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Calculates a confidence score [0.0, 1.0] for an evidence object.
 *
 * Scoring model:
 *   data_breadth    (0.30) — fraction of non-null metric keys
 *   comparison      (0.25) — presence of multi-row comparative data
 *   query_volume    (0.20) — more backing queries = higher confidence
 *   comparative_ctx (0.25) — period-over-period / peer comparisons derived
 */
@Component
public class ConfidenceCalculator {

    private static final int QUERY_CAP       = 5;
    private static final int COMPARATIVE_CAP = 4;

    public double calculate(
            Map<String, Object> metrics,
            Map<String, Object> comparisons,
            Map<String, Object> signals,
            int queryCount,
            int comparativeContextCount
    ) {
        double dataBreadth  = metrics.isEmpty() ? 0.0
                : Math.min(0.30, 0.30 * (double) metrics.size() / Math.max(1, metrics.size()));

        double compScore    = comparisons.isEmpty() ? 0.0 : 0.25;

        double queryScore   = Math.min(0.20, 0.20 * queryCount / (double) QUERY_CAP);

        double ctxScore     = Math.min(0.25,
                0.25 * comparativeContextCount / (double) COMPARATIVE_CAP);

        return Math.min(1.0, dataBreadth + compScore + queryScore + ctxScore);
    }
}
