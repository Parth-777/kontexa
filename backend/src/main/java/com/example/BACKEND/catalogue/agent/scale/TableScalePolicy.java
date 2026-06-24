package com.example.BACKEND.catalogue.agent.scale;

import org.springframework.stereotype.Component;

@Component
public class TableScalePolicy {

    private final ScaleProperties properties;

    public TableScalePolicy(ScaleProperties properties) {
        this.properties = properties;
    }

    public ScaleTier tier(long rowCount) {
        if (!properties.isEnabled()) return ScaleTier.SMALL;
        if (rowCount >= properties.getTierLargeMinRows()) return ScaleTier.LARGE;
        if (rowCount >= properties.getTierMediumMinRows()) return ScaleTier.MEDIUM;
        return ScaleTier.SMALL;
    }

    public boolean allowRawSample(ScaleTier tier) {
        return tier == ScaleTier.SMALL;
    }

    public boolean allowRootCauseReAct(ScaleTier tier) {
        if (tier == ScaleTier.LARGE) return !properties.isLargeSkipRootCause();
        return true;
    }

    public boolean requireDateWindow(ScaleTier tier) {
        return tier == ScaleTier.MEDIUM || tier == ScaleTier.LARGE;
    }

    public ScaleProperties properties() {
        return properties;
    }
}
