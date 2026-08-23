package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.executive.InsightCandidate;
import com.example.BACKEND.catalogue.agent.executive.InsightLens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsSynthesisPacketBuilderTest {

    @Test
    @DisplayName("omits Sample rows SELECT * dumps from synthesis packet")
    void omitsRawSampleRows() {
        List<Map<String, Object>> hugeRows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("visitId", i);
            row.put("totals", Map.of("visits", 1, "hits", 4, "pageviews", 4, "bounces", 0,
                    "nested", "x".repeat(2000)));
            row.put("trafficSource", Map.of("source", "direct", "medium", "none",
                    "blob", "y".repeat(3000)));
            hugeRows.add(row);
        }
        CollectedData sample = new CollectedData(
                "Sample rows from ga_sessions_20160801",
                "SELECT * FROM ga_sessions_20160801 LIMIT 100",
                hugeRows);

        Map<String, Object> kpi = new HashMap<>();
        kpi.put("metric", "sessions");
        kpi.put("current", 1200);
        kpi.put("baseline", 1000);
        kpi.put("deltaPct", 20.0);
        CollectedData exec = new CollectedData(
                "KPI: sessions WoW",
                "SELECT ...",
                List.of(kpi));

        var packet = InsightsSynthesisPacketBuilder.build(List.of(sample, exec), Set.of());
        assertTrue(packet.stats().omittedSampleRows());
        assertFalse(packet.evidenceText().contains("Sample rows"));
        assertTrue(packet.evidenceText().contains("1200"));
        assertTrue(packet.evidenceText().contains("20.0") || packet.evidenceText().contains("20"));
        assertTrue(packet.stats().evidenceChars() < 5_000);
        assertTrue(packet.stats().evidenceChars() < InsightsSynthesisPacketBuilder.MAX_EVIDENCE_CHARS);
    }

    @Test
    @DisplayName("large catalogue dump is not present in evidence packet")
    void noFullCatalogueInPacket() {
        String fakeCatalogue = "{\"tables\":[{\"tableName\":\"t\",\"columns\":["
                + "\"columnName\",\"businessMeaning\"".repeat(5000)
                + "]}]}";
        CollectedData sample = new CollectedData(
                "Sample rows from t",
                "SELECT *",
                List.of(Map.of("a", fakeCatalogue)));

        var packet = InsightsSynthesisPacketBuilder.build(List.of(sample), Set.of());
        assertFalse(InsightsSynthesisPacketBuilder.containsFullCatalogueDump(packet.evidenceText()));
        assertTrue(packet.stats().omittedSampleRows());
    }

    @Test
    @DisplayName("numeric evidence survives projection exactly")
    void numericEvidenceExact() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("revenue", 42.5);
        row.put("segment", "Enterprise");
        row.put("unrelated_blob", Map.of("x", "z".repeat(5000)));
        CollectedData cd = new CollectedData("DISCOVERY: revenue by segment", "sql", List.of(row));

        var packet = InsightsSynthesisPacketBuilder.build(List.of(cd), Set.of("revenue", "segment"));
        assertTrue(packet.evidenceText().contains("42.5"));
        assertTrue(packet.evidenceText().contains("Enterprise"));
        assertFalse(packet.evidenceText().contains("zzzz"));
    }

    @Test
    @DisplayName("candidate columns preserved; unrelated columns excluded when allow-list set")
    void candidateColumnsPreserved() {
        Map<String, Object> row = new HashMap<>();
        row.put("revenue", 100);
        row.put("noise_col", "should-drop");
        row.put("country", "US");
        CollectedData cd = new CollectedData("EXEC: revenue by country", "sql", List.of(row));

        InsightCandidate cand = new InsightCandidate(
                "Revenue up in US", InsightLens.GROWTH, "t", 50, "OPPORTUNITY", "HIGH",
                List.of("EXEC: revenue by country"),
                List.of(new AgentDashboardResult.MetricHighlight("revenue", "100")),
                List.of("revenue", "country"),
                "Sales", "US");

        var packet = InsightsSynthesisPacketBuilder.build(
                List.of(cd), InsightsSynthesisPacketBuilder.columnsFromCandidates(List.of(cand)));
        assertTrue(packet.evidenceText().contains("100"));
        assertTrue(packet.evidenceText().contains("US"));
        assertFalse(packet.evidenceText().contains("should-drop"));
    }

    @Test
    @DisplayName("hundreds of wide nested rows stay under evidence budget")
    void boundsLargeResultSets() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("day", "2024-01-" + (i % 28 + 1));
            r.put("sessions", i);
            r.put("nested", Map.of("a", "b".repeat(1000), "c", List.of(1, 2, 3, 4, 5, 6)));
            rows.add(r);
        }
        CollectedData trend = new CollectedData("Trend: sessions over time", "sql", rows);
        var packet = InsightsSynthesisPacketBuilder.build(List.of(trend), Set.of());
        assertTrue(packet.stats().evidenceChars() <= InsightsSynthesisPacketBuilder.MAX_EVIDENCE_CHARS);
        assertTrue(packet.stats().rowsIncluded() <= InsightsSynthesisPacketBuilder.MAX_ROWS_PER_DATASET);
        assertTrue(packet.evidenceText().contains("sessions"));
    }

    @Test
    @DisplayName("schema overview excludes unrelated catalogue columns")
    void schemaExcludesUnrelated() {
        var hints = List.of(new KpiDetectorService.ColumnHints(
                "ga_sessions",
                null,
                "date",
                List.of("country", "device", "unrelated_dim_a", "unrelated_dim_b"),
                List.of("sessions", "bounces", "unrelated_metric")));
        String schema = InsightsSynthesisPacketBuilder.schemaOverview(
                hints, Set.of("sessions", "country"), Map.of());
        assertTrue(schema.contains("sessions"));
        assertTrue(schema.contains("country"));
        assertFalse(schema.contains("unrelated_dim_a"));
        assertFalse(schema.contains("unrelated_metric"));
    }

    @Test
    @DisplayName("quality gate still rejects filler independently of packet")
    void qualityGateStillRejectsFiller() {
        InsightQualityGate gate = new InsightQualityGate();
        var card = new AgentDashboardResult.InsightCard(
                "Enhance data integration processes",
                "Upgrade analytics platform capabilities.",
                "MEDIUM");
        assertEquals(0, gate.filter(List.of(card)).kept().size());
    }
}
