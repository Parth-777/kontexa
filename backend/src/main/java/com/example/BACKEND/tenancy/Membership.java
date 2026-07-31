package com.example.BACKEND.tenancy;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Membership of a user in a tenant.
 * Physically stored in {@code workspace_memberships}.
 */
public record Membership(
        UUID id,
        UUID tenantId,
        UUID userId,
        TenantRole role,
        LocalDateTime createdAt
) {}
