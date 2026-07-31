package com.example.BACKEND.tenancy;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User identity.
 * Physically stored in {@code identities}. Phase A keeps legacy {@code workspace_id}
 * on the row for compatibility; membership is the authoritative tenant link.
 */
public record User(
        UUID id,
        String email,
        String displayName,
        String authProvider,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_INVITED = "INVITED";

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }
}
