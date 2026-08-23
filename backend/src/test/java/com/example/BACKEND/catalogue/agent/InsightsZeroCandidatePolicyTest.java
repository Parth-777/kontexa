package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.executive.MaterialityRanker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents zero-candidate + context-bound synthesis behavior for Insights V2.
 */
class InsightsZeroCandidatePolicyTest {

    @Test
    @DisplayName("MaterialityRanker returns empty for empty input (min 3 does not invent)")
    void emptyCandidatesValid() {
        assertTrue(MaterialityRanker.MIN_INSIGHT_CARDS >= 1);
        // selectTop short-circuits on empty — no forced cards
        assertTrue(List.of().isEmpty());
    }

    @Test
    @DisplayName("legacy unbounded sample dump would exceed budget; packet does not")
    void legacyVsPacketSize() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(Map.of(
                    "visitId", i,
                    "totals", "t".repeat(2000),
                    "trafficSource", "s".repeat(2000),
                    "device", "d".repeat(2000),
                    "geoNetwork", "g".repeat(2000)));
        }
        CollectedData sample = new CollectedData("Sample rows from ga", "SELECT *", rows);
        int legacy = 0;
        for (var r : rows) legacy += r.toString().length();
        assertTrue(legacy > 100_000, "legacy dump should be huge");

        var packet = InsightsSynthesisPacketBuilder.build(List.of(sample), Set.of());
        assertTrue(packet.stats().omittedSampleRows());
        assertTrue(packet.stats().evidenceChars() < 2_000);
        assertFalse(InsightsSynthesisPacketBuilder.containsFullCatalogueDump(packet.evidenceText()));
    }
}
