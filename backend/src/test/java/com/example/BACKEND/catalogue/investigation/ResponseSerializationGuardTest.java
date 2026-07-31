package com.example.BACKEND.catalogue.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ResponseSerializationGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponseSerializationGuard guard = new ResponseSerializationGuard();

    @Test
    @DisplayName("execution_path.sample_rows with nested list values serialize cleanly")
    void sampleRowsWithNestedValuesSerialize() {
        List<Object> recordLike = new ArrayList<>();
        recordLike.add(Map.of("country", "United States", "city", "New York"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", UUID.randomUUID().toString());
        response.put("status", "COMPLETED");

        Map<String, Object> executionPath = new LinkedHashMap<>();
        executionPath.put("sample_rows", List.of(Map.of("member", recordLike, "value", 100)));
        response.put("execution_path", executionPath);

        Map<String, Object> sanitized = guard.sanitizeResponse(response);
        assertDoesNotThrow(() -> mapper.writeValueAsString(sanitized));
    }
}
