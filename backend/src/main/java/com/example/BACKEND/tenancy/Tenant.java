package com.example.BACKEND.tenancy;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tenant aggregate root (company / workspace).
 * Physically stored in {@code workspaces}; {@code id} is the canonical tenantId.
 */
public record Tenant(
        UUID id,
        String name,
        String slug,
        String status,
        LocalDateTime createdAt
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_PROVISIONING = "PROVISIONING";

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }
}
