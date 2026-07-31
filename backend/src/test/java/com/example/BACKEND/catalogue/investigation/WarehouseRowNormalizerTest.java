package com.example.BACKEND.catalogue.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WarehouseRowNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Nested maps and lists serialize without SDK types")
    void nestedStructuresSerializeCleanly() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("country", "United States");
        nested.put("city", "New York");

        List<Object> recordLike = new ArrayList<>();
        recordLike.add(nested);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("member", recordLike);
        row.put("value", 42);

        Map<String, Object> normalized = WarehouseRowNormalizer.normalizeRow(row);

        assertDoesNotThrow(() -> mapper.writeValueAsString(normalized));
        assertInstanceOf(List.class, normalized.get("member"));
        assertEquals(42, normalized.get("value"));
    }

    @Test
    @DisplayName("Unknown connector objects stringify instead of leaking")
    void unknownObjectsStringify() {
        Object leaky = new Object() {
            @Override
            public String toString() {
                return "sdk-like-value";
            }
        };

        Map<String, Object> row = Map.of("member", leaky);
        Map<String, Object> normalized = WarehouseRowNormalizer.normalizeRow(row);

        assertEquals("sdk-like-value", normalized.get("member"));
        assertDoesNotThrow(() -> mapper.writeValueAsString(normalized));
    }
}
