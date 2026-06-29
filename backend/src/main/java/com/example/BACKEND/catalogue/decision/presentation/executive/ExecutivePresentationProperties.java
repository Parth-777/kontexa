package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kontexa.presentation")
public class ExecutivePresentationProperties {

    /** Default number of rows shown in ranking presentations. */
    private int rankingDefaultRows = 5;

    /** Chart-specific presentation settings. */
    private final Chart chart = new Chart();

    public int getRankingDefaultRows() {
        return rankingDefaultRows;
    }

    public void setRankingDefaultRows(int rankingDefaultRows) {
        this.rankingDefaultRows = rankingDefaultRows;
    }

    public Chart getChart() {
        return chart;
    }

    /**
     * Cardinality-aware chart rendering settings.
     * Bound from {@code kontexa.presentation.chart.*}.
     */
    public static class Chart {

        /**
         * Maximum number of grouped categories visualized before Top-N (+ "Other") aggregation.
         * Bound from {@code kontexa.presentation.chart.top-n}. Default 5.
         */
        private int topN = 5;

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }
    }
}
