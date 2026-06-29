package com.example.BACKEND.catalogue.decision.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks driver contributions by absolute impact and computes cumulative coverage of the
 * headline delta. Drivers opposing the headline direction are surfaced as counter-evidence.
 */
@Component
public class DriverRanker {

    public Result rank(List<DriverContribution> contributions, double headlineDelta, int topN) {
        List<DriverContribution> sorted = new ArrayList<>(contributions);
        sorted.sort(Comparator.comparingDouble(
                (DriverContribution c) -> Math.abs(c.absoluteContribution())).reversed());

        double denom = Math.abs(headlineDelta);
        List<RankedDriver> ranked = new ArrayList<>();
        List<RankedDriver> counter = new ArrayList<>();
        double cumulativeAligned = 0.0;
        int rank = 0;
        for (DriverContribution c : sorted) {
            rank++;
            if (c.alignedWithHeadline()) {
                cumulativeAligned += Math.abs(c.absoluteContribution());
            }
            double coverage = denom != 0.0 ? (cumulativeAligned / denom) * 100.0 : 0.0;
            RankedDriver rd = new RankedDriver(rank, c, coverage);
            if (c.alignedWithHeadline()) {
                if (ranked.size() < topN) {
                    ranked.add(rd);
                }
            } else {
                if (counter.size() < topN) {
                    counter.add(rd);
                }
            }
        }

        double explainedPct = denom != 0.0 ? (alignedSum(contributions) / denom) * 100.0 : 0.0;
        double residualPct = Math.max(0.0, 100.0 - explainedPct);
        return new Result(ranked, counter, explainedPct, residualPct);
    }

    private static double alignedSum(List<DriverContribution> contributions) {
        double sum = 0.0;
        for (DriverContribution c : contributions) {
            if (c.alignedWithHeadline()) {
                sum += Math.abs(c.absoluteContribution());
            }
        }
        return sum;
    }

    public record Result(
            List<RankedDriver> rankedDrivers,
            List<RankedDriver> counterEvidence,
            double explainedPct,
            double residualPct
    ) {}
}
