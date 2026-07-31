package com.example.BACKEND.catalogue.investigation;

/**
 * Confirmed headline contrast between focal and baseline slices.
 */
public record HeadlineGap(
        double baselineValue,
        double focalValue,
        double absoluteGap,
        double percentGap,
        String classification,
        boolean material
) {
    public static final String FOCAL_HIGHER = "FOCAL_HIGHER";
    public static final String FOCAL_LOWER = "FOCAL_LOWER";
    public static final String FLAT = "FLAT";
}
