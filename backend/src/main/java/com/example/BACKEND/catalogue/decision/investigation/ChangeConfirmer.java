package com.example.BACKEND.catalogue.decision.investigation;

import org.springframework.stereotype.Component;

/**
 * Confirms whether the target metric moved materially between baseline and observation
 * windows. The observed direction is reported from the data, independent of the direction
 * the question framed.
 */
@Component
public class ChangeConfirmer {

    public MetricChange confirm(
            String metricColumn,
            double baselineValue,
            double observationValue,
            String baselineSpecKey,
            String observationSpecKey,
            double materialityThresholdPct
    ) {
        double absoluteDelta = observationValue - baselineValue;
        double percentDelta;
        if (baselineValue != 0.0) {
            percentDelta = (absoluteDelta / Math.abs(baselineValue)) * 100.0;
        } else {
            percentDelta = absoluteDelta == 0.0 ? 0.0 : 100.0 * Math.signum(absoluteDelta);
        }

        String direction;
        if (absoluteDelta > 0) {
            direction = MetricChange.INCREASE;
        } else if (absoluteDelta < 0) {
            direction = MetricChange.DECREASE;
        } else {
            direction = MetricChange.FLAT;
        }

        boolean material = Math.abs(percentDelta) >= materialityThresholdPct && absoluteDelta != 0.0;

        return new MetricChange(
                metricColumn,
                baselineValue,
                observationValue,
                absoluteDelta,
                percentDelta,
                direction,
                material,
                baselineSpecKey,
                observationSpecKey);
    }
}
