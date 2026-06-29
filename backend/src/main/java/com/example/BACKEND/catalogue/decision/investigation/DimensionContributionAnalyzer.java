package com.example.BACKEND.catalogue.decision.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the signed contribution of each dimension member to the headline change.
 *
 * <p>For additive measures the contribution of a member is simply its observation value
 * minus its baseline value; the member contributions of a dimension sum to the headline
 * delta. Contribution percentage is expressed relative to the headline delta.
 */
@Component
public class DimensionContributionAnalyzer {

    public List<DriverContribution> analyze(MetricChange change, List<DimensionEvidence> evidence) {
        List<DriverContribution> contributions = new ArrayList<>();
        double headlineDelta = change.absoluteDelta();
        for (DimensionEvidence dim : evidence) {
            for (DimensionEvidence.MemberValue member : dim.members()) {
                double contribution = member.observationValue() - member.baselineValue();
                if (contribution == 0.0) {
                    continue;
                }
                double contributionPct = headlineDelta != 0.0
                        ? (contribution / headlineDelta) * 100.0
                        : 0.0;
                boolean aligned = Math.signum(contribution) == Math.signum(headlineDelta)
                        && headlineDelta != 0.0;
                contributions.add(new DriverContribution(
                        dim.dimensionColumn(),
                        dim.dimensionLabel(),
                        member.member(),
                        member.baselineValue(),
                        member.observationValue(),
                        contribution,
                        contributionPct,
                        aligned,
                        dim.observationSpecKey()));
            }
        }
        return contributions;
    }
}
