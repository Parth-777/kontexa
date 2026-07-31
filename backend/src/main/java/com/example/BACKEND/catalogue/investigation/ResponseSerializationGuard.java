package com.example.BACKEND.catalogue.investigation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Final API-boundary sanitizer applied before Jackson serializes decision responses.
 */
@Component
public class ResponseSerializationGuard {

    @SuppressWarnings("unchecked")
    public Map<String, Object> sanitizeResponse(Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            return response;
        }
        Object normalized = WarehouseRowNormalizer.normalizeValue(response);
        if (normalized instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return response;
    }
}
