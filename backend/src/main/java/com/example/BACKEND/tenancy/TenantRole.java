package com.example.BACKEND.tenancy;

/**
 * Tenant-scoped role. Maps 1:1 to workspace_memberships.role.
 */
public enum TenantRole {
    OWNER,
    ADMIN,
    ANALYST,
    VIEWER;

    public static TenantRole fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            return VIEWER;
        }
        try {
            return TenantRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return VIEWER;
        }
    }

    public String toDb() {
        return name();
    }
}
