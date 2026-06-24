package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kontexa.presentation")
public class ExecutivePresentationProperties {

    /** Default number of rows shown in ranking presentations. */
    private int rankingDefaultRows = 5;

    public int getRankingDefaultRows() {
        return rankingDefaultRows;
    }

    public void setRankingDefaultRows(int rankingDefaultRows) {
        this.rankingDefaultRows = rankingDefaultRows;
    }
}
