package com.example.BACKEND.tenant;

import com.example.BACKEND.identity.auth.ForbiddenException;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves the authenticated tenant key for legacy {@code client_id} columns.
 *
 * Authority is ALWAYS {@link TenantContext} from the session.
 * Client headers/body/query tenant fields are never used for selection;
 * if supplied and they disagree with the session tenant, the request is rejected.
 */
@Component
public class TenantContextResolver {

    /**
     * Returns the authenticated workspace slug (legacy client/tenant key).
     * Still required for DecisionRuntime / catalogue adapters that key by slug.
     */
    public String requireLegacyTenantKey() {
        return TenantContextHolder.require().legacyTenantKey();
    }

    /** Canonical UUID tenant id ({@code workspaces.id}). */
    public java.util.UUID requireTenantId() {
        return TenantContextHolder.require().tenantId();
    }

    public TenantContext requireContext() {
        return TenantContextHolder.require();
    }

    /**
     * Resolves authenticated {@link TenantContext}; validates optional client hints.
     */
    public TenantContext resolveContext(String headerClientId, Map<String, ?> requestBody) {
        TenantContext ctx = requireContext();
        String tenantKey = ctx.legacyTenantKey();
        assertHintMatches(tenantKey, headerClientId);
        if (requestBody != null) {
            assertHintMatches(tenantKey, stringValue(requestBody.get("clientId")));
            assertHintMatches(tenantKey, stringValue(requestBody.get("tenantId")));
            assertHintMatches(tenantKey, stringValue(requestBody.get("tenantSchema")));
        }
        return ctx;
    }

    public TenantContext resolveContext(String headerClientId, String... queryHints) {
        TenantContext ctx = requireContext();
        String tenantKey = ctx.legacyTenantKey();
        assertHintMatches(tenantKey, headerClientId);
        if (queryHints != null) {
            for (String hint : queryHints) {
                assertHintMatches(tenantKey, hint);
            }
        }
        return ctx;
    }

    /**
     * Session tenant key only. Optional client hints are validated for mismatch.
     *
     * @param headerClientId optional {@code X-Client-Id}
     * @param requestBody    optional body that may contain {@code clientId}/{@code tenantId}/{@code tenantSchema}
     */
    public String resolveClientId(String headerClientId, Map<String, ?> requestBody) {
        return resolveContext(headerClientId, requestBody).legacyTenantKey();
    }

    /**
     * Session tenant key; validates optional query/header hints.
     */
    public String resolveClientId(String headerClientId, String... queryHints) {
        return resolveContext(headerClientId, queryHints).legacyTenantKey();
    }

    public void assertHintMatches(String authenticatedTenantKey, String clientHint) {
        if (clientHint == null || clientHint.isBlank()) {
            return;
        }
        if (!authenticatedTenantKey.equals(clientHint.trim())) {
            throw new ForbiddenException(
                    "Tenant mismatch between request and authenticated workspace.");
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? null : s;
    }
}
