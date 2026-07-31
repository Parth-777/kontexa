package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

/**
 * Stage-1 materiality gate: converts focal/baseline totals into a {@link HeadlineGap}.
 */
@Component
public class HeadlineGapAnalyzer {

    private final InvestigationProperties properties;

    public HeadlineGapAnalyzer(InvestigationProperties properties) {
        this.properties = properties;
    }

    public HeadlineGap analyze(double baselineValue, double focalValue) {
        double absoluteGap = focalValue - baselineValue;
        double percentGap = baselineValue == 0.0
                ? (absoluteGap == 0.0 ? 0.0 : Double.POSITIVE_INFINITY)
                : (absoluteGap / Math.abs(baselineValue)) * 100.0;

        String classification;
        if (absoluteGap > 0) {
            classification = HeadlineGap.FOCAL_HIGHER;
        } else if (absoluteGap < 0) {
            classification = HeadlineGap.FOCAL_LOWER;
        } else {
            classification = HeadlineGap.FLAT;
        }

        boolean material = Double.isInfinite(percentGap)
                || Math.abs(percentGap) >= properties.getMaterialityThresholdPct();

        return new HeadlineGap(baselineValue, focalValue, absoluteGap, percentGap,
                classification, material);
    }
}
