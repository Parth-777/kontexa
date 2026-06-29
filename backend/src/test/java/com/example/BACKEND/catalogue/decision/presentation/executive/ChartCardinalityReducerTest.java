package com.example.BACKEND.catalogue.decision.presentation.executive;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartCardinalityReducerTest {

    private static final String CATEGORY = "segment";
    private static final String VALUE = "total_amount";

    private final ChartCardinalityReducer reducer = new ChartCardinalityReducer(5);

    @Test
    void threeRowsRenderAll() {
        ChartCardinalityReducer.Result result =
                reduceDistribution(rows(3));

        assertEquals(3, result.data().size());
        assertEquals(3, result.displayedRows());
        assertEquals(3, result.totalRows());
        assertEquals(0, result.aggregatedRows());
        assertNull(result.notice());
        assertNoOther(result);
    }

    @Test
    void fiveRowsRenderAll() {
        ChartCardinalityReducer.Result result =
                reduceDistribution(rows(5));

        assertEquals(5, result.data().size());
        assertEquals(5, result.displayedRows());
        assertEquals(5, result.totalRows());
        assertEquals(0, result.aggregatedRows());
        assertNull(result.notice());
        assertNoOther(result);
    }

    @Test
    void sixRowsCollapseToTopFivePlusOther() {
        ChartCardinalityReducer.Result result =
                reduceDistribution(rows(6));

        // Top 5 individual categories + a single "Other" bucket.
        assertEquals(6, result.data().size());
        assertEquals(5, result.displayedRows());
        assertEquals(6, result.totalRows());
        assertEquals(1, result.aggregatedRows());

        Map<String, Object> other = result.data().getLast();
        assertEquals(ChartCardinalityReducer.OTHER_LABEL, other.get(CATEGORY));
        // Rows had values 1..6; the 6th smallest (value 1) is aggregated into "Other".
        assertEquals(1.0, ((Number) other.get(VALUE)).doubleValue(), 0.0001);
    }

    @Test
    void manyRowsCollapseToTopFivePlusDeterministicOther() {
        ChartCardinalityReducer.Result result =
                reduceDistribution(rows(260));

        assertEquals(6, result.data().size());
        assertEquals(5, result.displayedRows());
        assertEquals(260, result.totalRows());
        assertEquals(255, result.aggregatedRows());

        // Values are 1..260. Top 5 = 256..260. "Other" = sum(1..255) = 255*256/2 = 32640.
        Map<String, Object> other = result.data().getLast();
        assertEquals(ChartCardinalityReducer.OTHER_LABEL, other.get(CATEGORY));
        assertEquals(32640.0, ((Number) other.get(VALUE)).doubleValue(), 0.0001);

        assertTrue(result.notice().contains("Top 5 of 260"));
        assertTrue(result.notice().contains("Other"));
    }

    @Test
    void rankingKeepsTopFiveWithoutOther() {
        ChartCardinalityReducer.Result result =
                reducer.reduce("RANKING", "HBAR", CATEGORY, VALUE, rows(260));

        assertEquals(5, result.data().size());
        assertEquals(5, result.displayedRows());
        assertEquals(260, result.totalRows());
        assertEquals(0, result.aggregatedRows());
        assertNoOther(result);
        assertTrue(result.notice().contains("Top 5 of 260"));
    }

    @Test
    void trendIsNeverTruncated() {
        ChartCardinalityReducer.Result result =
                reducer.reduce("TREND", "LINE", null, VALUE, rows(260));

        assertEquals(260, result.data().size());
        assertEquals(260, result.displayedRows());
        assertEquals(260, result.totalRows());
        assertEquals(0, result.aggregatedRows());
        assertNull(result.notice());
    }

    @Test
    void topRowsAreSortedByValueDescending() {
        ChartCardinalityReducer.Result result =
                reduceDistribution(rows(6));

        double previous = Double.POSITIVE_INFINITY;
        for (int i = 0; i < result.displayedRows(); i++) {
            double v = ((Number) result.data().get(i).get(VALUE)).doubleValue();
            assertTrue(v <= previous, "rows must be sorted descending by value");
            previous = v;
        }
    }

    private ChartCardinalityReducer.Result reduceDistribution(List<Map<String, Object>> rows) {
        return reducer.reduce("DISTRIBUTION", "HBAR", CATEGORY, VALUE, rows);
    }

    private static void assertNoOther(ChartCardinalityReducer.Result result) {
        boolean hasOther = result.data().stream()
                .anyMatch(r -> ChartCardinalityReducer.OTHER_LABEL.equals(r.get(CATEGORY)));
        assertTrue(!hasOther, "no \"Other\" bucket expected");
    }

    /** Generates {@code count} category rows with values 1..count. */
    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(CATEGORY, "cat-" + i);
            row.put(VALUE, i);
            rows.add(row);
        }
        return rows;
    }
}
