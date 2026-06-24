package com.example.BACKEND.catalogue.decision.execution.repair;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntermediateResultInspectorTest {

    private final IntermediateResultInspector inspector = new IntermediateResultInspector();

    @Test
    void emptyRows_fails() {
        var r = inspector.inspect(List.of());
        assertFalse(r.acceptable());
        assertEquals("EMPTY", r.issue());
    }

    @Test
    void singleGroup_fails() {
        var r = inspector.inspect(List.of(row("weekend", "Weekday", 1000)));
        assertFalse(r.acceptable());
        assertEquals("SINGLE_VALUE", r.issue());
    }

    @Test
    void multipleDistinctGroups_passes() {
        var r = inspector.inspect(List.of(
                row("bucket", "0-1", 100),
                row("bucket", "1-3", 200),
                row("bucket", "3-5", 150)));
        assertTrue(r.acceptable());
        assertEquals(3, r.distinctGroups());
    }

    private Map<String, Object> row(String dim, String val, double revenue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(dim, val);
        m.put("revenue", revenue);
        return m;
    }
}
