package com.example.BACKEND.catalogue.decision.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Investigation Runtime V1 (CHANGE-mode, dimension drivers).
 * Bound from {@code kontexa.investigation.*}.
 */
@Component
@ConfigurationProperties(prefix = "kontexa.investigation")
public class InvestigationProperties {

    /** Minimum absolute percent change for a metric movement to be considered material. */
    private double materialityThresholdPct = 2.0;

    /** Maximum number of candidate dimensions decomposed per investigation. */
    private int maxCandidateDimensions = 6;

    /** Maximum number of members retained per dimension breakdown query. */
    private int maxMembersPerDimension = 50;

    /** Maximum number of ranked drivers retained in the Evidence Pack. */
    private int topDriversInPack = 10;

    private final Window window = new Window();

    public double getMaterialityThresholdPct() {
        return materialityThresholdPct;
    }

    public void setMaterialityThresholdPct(double materialityThresholdPct) {
        this.materialityThresholdPct = materialityThresholdPct;
    }

    public int getMaxCandidateDimensions() {
        return maxCandidateDimensions;
    }

    public void setMaxCandidateDimensions(int maxCandidateDimensions) {
        this.maxCandidateDimensions = maxCandidateDimensions;
    }

    public int getMaxMembersPerDimension() {
        return maxMembersPerDimension;
    }

    public void setMaxMembersPerDimension(int maxMembersPerDimension) {
        this.maxMembersPerDimension = maxMembersPerDimension;
    }

    public int getTopDriversInPack() {
        return topDriversInPack;
    }

    public void setTopDriversInPack(int topDriversInPack) {
        this.topDriversInPack = topDriversInPack;
    }

    public Window getWindow() {
        return window;
    }

    /**
     * Change-window derivation settings. Bound from {@code kontexa.investigation.window.*}.
     */
    public static class Window {

        /** Grain used when the planner did not specify a time grain. */
        private String defaultGrain = "MONTH";

        public String getDefaultGrain() {
            return defaultGrain;
        }

        public void setDefaultGrain(String defaultGrain) {
            this.defaultGrain = defaultGrain;
        }
    }
}
