package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 2 (Investigation Runtime V1) — Stage-2 ranking. Orders driver contributions by
 * absolute impact, applies the per-member contribution floor, caps the pack size, and
 * computes cumulative coverage of the headline delta. Deterministic; no GPT.
 */
@Component
public class DriverRanker {

    private final InvestigationProperties properties;

    public DriverRanker(InvestigationProperties properties) {
        this.properties = properties;
    }

    public List<RankedDriver> rank(List<DriverContribution> contributions) {
        List<RankedDriver> ranked = new ArrayList<>();
        if (contributions == null || contributions.isEmpty()) {
            return ranked;
        }

        double floor = properties.getDriverContributionFloorPct();
        int cap = properties.getTopDriversInPack();

        List<DriverContribution> filtered = new ArrayList<>();
        for (DriverContribution c : contributions) {
            if (Math.abs(c.contributionPctOfGap()) >= floor) {
                filtered.add(c);
            }
        }
        filtered.sort(Comparator.comparingDouble(
                (DriverContribution c) -> Math.abs(c.signedContribution())).reversed());

        double cumulative = 0.0;
        int rank = 1;
        for (DriverContribution c : filtered) {
            if (rank > cap) {
                break;
            }
            cumulative += c.contributionPctOfGap();
            ranked.add(new RankedDriver(rank, c, cumulative));
            rank++;
        }
        return ranked;
    }
}
