package com.example.BACKEND.catalogue.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 2 (Investigation Runtime V1) configuration holder.
 *
 * <p>Binds the {@code kontexa.investigation.*} properties. This is a passive config
 * bean only — it holds tunables (materiality thresholds, candidate/member caps,
 * window grain) and contains no execution logic. Defaults apply when no property is
 * set, so it requires no change to application.properties for Step 1.
 *
 * <p>It does not reference or import any Phase 1 class and creates no code path from
 * {@code DecisionRuntime} into Phase 2.
 */
@Component
@ConfigurationProperties(prefix = "kontexa.investigation")
public class InvestigationProperties {

    /** Minimum |%Δ| of the headline metric required to investigate (Stage-1 gate). */
    private double materialityThresholdPct = 2.0;

    /** Minimum |contribution %Δ| for a single member to be reported (Stage-2 gate). */
    private double driverContributionFloorPct = 1.0;

    /** Maximum number of catalogue dimensions decomposed per investigation. */
    private int maxCandidateDimensions = 6;

    /** Maximum members fetched per dimension before truncation/aggregation. */
    private int maxMembersPerDimension = 50;

    /** Maximum ranked drivers retained in the Evidence Pack. */
    private int topDriversInPack = 10;

    private final Window window = new Window();

    public static class Window {
        /** Default comparison grain when the spec does not specify one. */
        private String defaultGrain = "MONTH";

        public String getDefaultGrain() {
            return defaultGrain;
        }

        public void setDefaultGrain(String defaultGrain) {
            this.defaultGrain = defaultGrain;
        }
    }

    public double getMaterialityThresholdPct() {
        return materialityThresholdPct;
    }

    public void setMaterialityThresholdPct(double materialityThresholdPct) {
        this.materialityThresholdPct = materialityThresholdPct;
    }

    public double getDriverContributionFloorPct() {
        return driverContributionFloorPct;
    }

    public void setDriverContributionFloorPct(double driverContributionFloorPct) {
        this.driverContributionFloorPct = driverContributionFloorPct;
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
}
