package com.example.BACKEND.tenant;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TenantContextResolver {

    public String resolveClientId(String headerClientId, Map<String, Object> requestBody) {
        if (headerClientId != null && !headerClientId.isBlank()) {
            return headerClientId.trim();
        }
        if (requestBody != null) {
            Object bodyClientId = requestBody.get("clientId");
            if (bodyClientId != null && !String.valueOf(bodyClientId).isBlank()) {
                return String.valueOf(bodyClientId).trim();
            }
            Object tenantSchema = requestBody.get("tenantSchema");
            if (tenantSchema != null && !String.valueOf(tenantSchema).isBlank()) {
                return String.valueOf(tenantSchema).trim();
            }
        }
        throw new IllegalArgumentException("Missing tenant context. Provide X-Client-Id header.");
    }
}
