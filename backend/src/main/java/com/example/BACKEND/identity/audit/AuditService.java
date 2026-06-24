package com.example.BACKEND.identity.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    public static final String LOGIN           = "LOGIN";
    public static final String LOGIN_FAILED      = "LOGIN_FAILED";
    public static final String LOGOUT            = "LOGOUT";
    public static final String CONNECTOR_CHANGE  = "CONNECTOR_CHANGE";
    public static final String ROLE_CHANGE       = "ROLE_CHANGE";
    public static final String INVITE_SENT       = "INVITE_SENT";
    public static final String INVITE_RESENT     = "INVITE_RESENT";
    public static final String INVITE_REVOKED    = "INVITE_REVOKED";
    public static final String INVITE_ACCEPTED   = "INVITE_ACCEPTED";
    public static final String PASSWORD_RESET    = "PASSWORD_RESET";
    public static final String WORKSPACE_SWITCH  = "WORKSPACE_SWITCH";

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEnabled() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='auth_audit_events'",
                Integer.class);
        return n != null && n > 0;
    }

    public void log(UUID workspaceId, UUID identityId, String eventType,
                    String detail, String ipAddress, String userAgent) {
        if (!isEnabled()) return;
        jdbc.update("""
                INSERT INTO auth_audit_events (workspace_id, identity_id, event_type, event_detail, ip_address, user_agent)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                workspaceId, identityId, eventType, detail, ipAddress, userAgent);
    }
}
