package com.example.BACKEND.catalogue.agent.scale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kontexa.scale")
public class ScaleProperties {

    private boolean enabled = true;

    private long tierMediumMinRows = 100_000L;
    private long tierLargeMinRows  = 1_000_000L;

    private int windowMediumDays = 730;
    /** LARGE tier: number of most recent distinct dates in the date column (not calendar days from today). */
    private int windowLargeDays    = 150;

    /** How many categorical columns to profile for distribution-based insights. */
    private int insightDistributionDims = 3;
    /** Segment dimensions scanned by GeneralDiscoveryAgent. */
    private int insightScanDimensions = 6;
    /** Max warehouse probes per table for executive discovery patterns. */
    private int discoveryMaxProbesPerTable = 12;
    /** Max warehouse probes per table for revenue model analysis. */
    private int revenueMaxProbesPerTable = 16;

    private int guardMaxResultRows   = 500;
    private int guardMaxLimitClause    = 500;
    private long guardBigqueryMaxBytesPerQuery = 5L * 1024 * 1024 * 1024;
    private long guardBigqueryMaxBytesPerRun   = 50L * 1024 * 1024 * 1024;

    private int schedulerTenantTimeoutMinutes = 10;
    private int schedulerMaxQueriesPerTenant    = 40;
    private int schedulerParallelTenants        = 3;

    private int largeMaxMetrics     = 2;
    private int largeMaxDimensions  = 1;
    private boolean largeSkipRootCause = true;

    private int mediumMaxMetrics    = 4;
    private int mediumMaxDimensions = 3;

    private int smallMaxMetrics     = 4;
    private int smallMaxDimensions  = 3;

    private boolean rollupEnabled = true;
    private int rollupMaxAgeHours = 36;
    private int rollupMinCoveragePct = 85;
    private double rollupMaxCountDriftPct = 5.0;
    private double rollupEquivalenceMaxRelativeError = 0.001;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getTierMediumMinRows() { return tierMediumMinRows; }
    public void setTierMediumMinRows(long v) { this.tierMediumMinRows = v; }

    public long getTierLargeMinRows() { return tierLargeMinRows; }
    public void setTierLargeMinRows(long v) { this.tierLargeMinRows = v; }

    public int getWindowMediumDays() { return windowMediumDays; }
    public void setWindowMediumDays(int v) { this.windowMediumDays = v; }

    public int getWindowLargeDays() { return windowLargeDays; }
    public void setWindowLargeDays(int v) { this.windowLargeDays = v; }

    public int getInsightDistributionDims() { return insightDistributionDims; }
    public void setInsightDistributionDims(int v) { this.insightDistributionDims = v; }

    public int getInsightScanDimensions() { return insightScanDimensions; }
    public void setInsightScanDimensions(int v) { this.insightScanDimensions = v; }

    public int getDiscoveryMaxProbesPerTable() { return discoveryMaxProbesPerTable; }
    public void setDiscoveryMaxProbesPerTable(int v) { this.discoveryMaxProbesPerTable = v; }

    public int getRevenueMaxProbesPerTable() { return revenueMaxProbesPerTable; }
    public void setRevenueMaxProbesPerTable(int v) { this.revenueMaxProbesPerTable = v; }

    public int getGuardMaxResultRows() { return guardMaxResultRows; }
    public void setGuardMaxResultRows(int v) { this.guardMaxResultRows = v; }

    public int getGuardMaxLimitClause() { return guardMaxLimitClause; }
    public void setGuardMaxLimitClause(int v) { this.guardMaxLimitClause = v; }

    public long getGuardBigqueryMaxBytesPerQuery() { return guardBigqueryMaxBytesPerQuery; }
    public void setGuardBigqueryMaxBytesPerQuery(long v) { this.guardBigqueryMaxBytesPerQuery = v; }

    public long getGuardBigqueryMaxBytesPerRun() { return guardBigqueryMaxBytesPerRun; }
    public void setGuardBigqueryMaxBytesPerRun(long v) { this.guardBigqueryMaxBytesPerRun = v; }

    public int getSchedulerTenantTimeoutMinutes() { return schedulerTenantTimeoutMinutes; }
    public void setSchedulerTenantTimeoutMinutes(int v) { this.schedulerTenantTimeoutMinutes = v; }

    public int getSchedulerMaxQueriesPerTenant() { return schedulerMaxQueriesPerTenant; }
    public void setSchedulerMaxQueriesPerTenant(int v) { this.schedulerMaxQueriesPerTenant = v; }

    public int getSchedulerParallelTenants() { return schedulerParallelTenants; }
    public void setSchedulerParallelTenants(int v) { this.schedulerParallelTenants = v; }

    public int getLargeMaxMetrics() { return largeMaxMetrics; }
    public void setLargeMaxMetrics(int v) { this.largeMaxMetrics = v; }

    public int getLargeMaxDimensions() { return largeMaxDimensions; }
    public void setLargeMaxDimensions(int v) { this.largeMaxDimensions = v; }

    public boolean isLargeSkipRootCause() { return largeSkipRootCause; }
    public void setLargeSkipRootCause(boolean v) { this.largeSkipRootCause = v; }

    public int getMediumMaxMetrics() { return mediumMaxMetrics; }
    public void setMediumMaxMetrics(int v) { this.mediumMaxMetrics = v; }

    public int getMediumMaxDimensions() { return mediumMaxDimensions; }
    public void setMediumMaxDimensions(int v) { this.mediumMaxDimensions = v; }

    public int getSmallMaxMetrics() { return smallMaxMetrics; }
    public void setSmallMaxMetrics(int v) { this.smallMaxMetrics = v; }

    public int getSmallMaxDimensions() { return smallMaxDimensions; }
    public void setSmallMaxDimensions(int v) { this.smallMaxDimensions = v; }

    public boolean isRollupEnabled() { return rollupEnabled; }
    public void setRollupEnabled(boolean v) { this.rollupEnabled = v; }

    public int getRollupMaxAgeHours() { return rollupMaxAgeHours; }
    public void setRollupMaxAgeHours(int v) { this.rollupMaxAgeHours = v; }

    public int getRollupMinCoveragePct() { return rollupMinCoveragePct; }
    public void setRollupMinCoveragePct(int v) { this.rollupMinCoveragePct = v; }

    public double getRollupMaxCountDriftPct() { return rollupMaxCountDriftPct; }
    public void setRollupMaxCountDriftPct(double v) { this.rollupMaxCountDriftPct = v; }

    public double getRollupEquivalenceMaxRelativeError() { return rollupEquivalenceMaxRelativeError; }
    public void setRollupEquivalenceMaxRelativeError(double v) { this.rollupEquivalenceMaxRelativeError = v; }

    public int maxMetrics(ScaleTier tier) {
        return switch (tier) {
            case LARGE -> largeMaxMetrics;
            case MEDIUM -> mediumMaxMetrics;
            case SMALL -> smallMaxMetrics;
        };
    }

    public int maxDimensions(ScaleTier tier) {
        return switch (tier) {
            case LARGE -> largeMaxDimensions;
            case MEDIUM -> mediumMaxDimensions;
            case SMALL -> smallMaxDimensions;
        };
    }

    public int windowDays(ScaleTier tier) {
        return tier == ScaleTier.LARGE ? windowLargeDays : windowMediumDays;
    }
}
