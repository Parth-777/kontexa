package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocates the headline gap to dimension members via additive partition attribution.
 */
@Component
public class AttributionModel {

    public List<DriverContribution> attribute(DimensionEvidence evidence, double headlineGap) {
        List<DriverContribution> out = new ArrayList<>();
        if (evidence == null || evidence.members() == null) {
            return out;
        }
        int gapSign = sign(headlineGap);
        for (DimensionEvidence.MemberValue m : evidence.members()) {
            double signedContribution = m.focalValue() - m.baselineValue();
            double contributionPctOfGap = headlineGap == 0.0
                    ? 0.0
                    : (signedContribution / headlineGap) * 100.0;
            boolean aligned = gapSign != 0 && sign(signedContribution) == gapSign;
            out.add(new DriverContribution(
                    evidence.dimensionColumn(),
                    m.member(),
                    m.baselineValue(),
                    m.focalValue(),
                    signedContribution,
                    contributionPctOfGap,
                    aligned));
        }
        return out;
    }

    private int sign(double v) {
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }
}
