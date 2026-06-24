package com.example.BACKEND.catalogue.semantic.phase2;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase-2 semantic planner feature flags.
 *
 * semantic.planner.mode: legacy | shadow | gpt
 */
@ConfigurationProperties(prefix = "semantic.planner")
public class SemanticPlanningProperties {

    public enum Mode {
        legacy, shadow, gpt
    }

    private Mode mode = Mode.legacy;
    private double minConfidence = 0.4;
    private String shadowLogPath = "target/phase2-shadow.log";
    private String fidelityLogPath = "target/semantic-fidelity.log";
    private String canonicalSqlShadowLogPath = "target/canonical-sql-shadow.log";
    private boolean shadowExecuteGptSql = true;
    private boolean canonicalShadowEnabled = true;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode != null ? mode : Mode.legacy;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public String getShadowLogPath() {
        return shadowLogPath;
    }

    public void setShadowLogPath(String shadowLogPath) {
        this.shadowLogPath = shadowLogPath;
    }

    public String getFidelityLogPath() {
        return fidelityLogPath;
    }

    public void setFidelityLogPath(String fidelityLogPath) {
        this.fidelityLogPath = fidelityLogPath;
    }

    public String getCanonicalSqlShadowLogPath() {
        return canonicalSqlShadowLogPath;
    }

    public void setCanonicalSqlShadowLogPath(String canonicalSqlShadowLogPath) {
        this.canonicalSqlShadowLogPath = canonicalSqlShadowLogPath;
    }

    public boolean isCanonicalShadowEnabled() {
        return canonicalShadowEnabled && runsGptPlanner();
    }

    public void setCanonicalShadowEnabled(boolean canonicalShadowEnabled) {
        this.canonicalShadowEnabled = canonicalShadowEnabled;
    }

    public boolean isShadowExecuteGptSql() {
        return shadowExecuteGptSql;
    }

    public void setShadowExecuteGptSql(boolean shadowExecuteGptSql) {
        this.shadowExecuteGptSql = shadowExecuteGptSql;
    }

    public boolean isLegacy() {
        return mode == Mode.legacy;
    }

    public boolean isShadow() {
        return mode == Mode.shadow;
    }

    public boolean isGpt() {
        return mode == Mode.gpt;
    }

    public boolean runsGptPlanner() {
        return mode == Mode.shadow || mode == Mode.gpt;
    }
}
