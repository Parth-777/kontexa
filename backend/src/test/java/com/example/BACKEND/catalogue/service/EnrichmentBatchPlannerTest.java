package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichmentBatchPlannerTest {

    @Test
    void packsByTokenBudgetNotFixedFieldCount() {
        EnrichmentBatchConfig config = new EnrichmentBatchConfig(
                16_384,
                0.50,
                12_000,
                2,
                420,
                1_800
        );

        List<ColumnInfo> tiny = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tiny.add(leaf("c" + i, "STRING", List.of("a")));
        }
        List<List<ColumnInfo>> tinyBatches = EnrichmentBatchPlanner.planLeafBatches(tiny, config);
        assertTrue(tinyBatches.size() >= 1);
        // 40 * ~420 = 16800 > 8192 budget → more than one batch expected
        assertTrue(tinyBatches.size() >= 2, "expected multiple batches for 40 rich leaves, got " + tinyBatches.size());

        List<ColumnInfo> fewHeavy = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fewHeavy.add(leaf("h" + i, "STRING", List.of("x".repeat(200), "y".repeat(200))));
        }
        List<List<ColumnInfo>> heavyBatches = EnrichmentBatchPlanner.planLeafBatches(fewHeavy, config);
        assertTrue(heavyBatches.size() >= 1);
        int total = heavyBatches.stream().mapToInt(List::size).sum();
        assertEquals(8, total);
    }

    @Test
    void neverDropsLeaves() {
        EnrichmentBatchConfig config = EnrichmentBatchConfig.defaults();
        List<ColumnInfo> leaves = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            leaves.add(leaf("f" + i, "INTEGER", List.of("1", "2")));
        }
        List<List<ColumnInfo>> batches = EnrichmentBatchPlanner.planLeafBatches(leaves, config);
        int total = batches.stream().mapToInt(List::size).sum();
        assertEquals(100, total);
        assertTrue(batches.size() > 1);
    }

    @Test
    void splitBatchHalves() {
        List<ColumnInfo> batch = List.of(
                leaf("a", "STRING", List.of()),
                leaf("b", "STRING", List.of()),
                leaf("c", "STRING", List.of()),
                leaf("d", "STRING", List.of())
        );
        List<List<ColumnInfo>> parts = EnrichmentBatchPlanner.splitBatch(batch);
        assertEquals(2, parts.size());
        assertEquals(2, parts.get(0).size());
        assertEquals(2, parts.get(1).size());
    }

    private static ColumnInfo leaf(String name, String type, List<String> samples) {
        ColumnInfo c = new ColumnInfo(name, type, true);
        c.setContainer(false);
        c.setSampleValues(new ArrayList<>(samples));
        return c;
    }
}
