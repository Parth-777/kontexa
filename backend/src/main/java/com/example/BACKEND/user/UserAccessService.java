package com.example.BACKEND.user;

import com.example.BACKEND.identity.auth.PasswordService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserAccessService {

    public record UserLoginResult(String userId, String tenantId, String position, String tenantSchema) {}

    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwords;

    public UserAccessService(JdbcTemplate jdbcTemplate, PasswordService passwords) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwords = passwords;
    }

    public Optional<UserLoginResult> authenticate(String userId, String password) {
        String sql = "SELECT * FROM public.app_users WHERE LOWER(user_id) = LOWER(?) LIMIT 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);
        if (rows.isEmpty()) return Optional.empty();

        Map<String, Object> row = rows.getFirst();
        String stored = readString(row, "password_hash", "password", "psw", "passwd");
        PasswordService.LoginPasswordCheck check = passwords.check(password, stored);
        if (!check.matched()) return Optional.empty();
        if (check.requiresRehash()) {
            jdbcTemplate.update(
                    "UPDATE public.app_users SET password_hash = ? WHERE LOWER(user_id) = LOWER(?)",
                    passwords.hash(password), userId);
        }

        Boolean active = readBoolean(row, "is_active", "active");
        if (active == null) active = true;
        if (Boolean.FALSE.equals(active)) return Optional.empty();

        String resolvedUserId = readString(row, "user_id", "userId");
        String tenantId = readString(row, "tenant_id", "tenantId");
        String schema = readString(row, "tenant_schema", "schema_name");
        if (schema == null || schema.isBlank()) {
            schema = inferSchemaFromTenantId(tenantId);
        }
        return Optional.of(new UserLoginResult(
                resolvedUserId == null ? userId : resolvedUserId,
                tenantId == null ? "" : tenantId,
                readString(row, "position", "role") == null ? "Viewer" : readString(row, "position", "role"),
                schema == null ? "" : schema
        ));
    }

    public List<Map<String, Object>> listUsers(String tenantId) {
        String sql = "SELECT * FROM public.app_users "
                + "WHERE LOWER(tenant_id)=LOWER(?) ORDER BY user_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId);
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", readString(row, "user_id", "userId"));
            item.put("tenantId", readString(row, "tenant_id", "tenantId"));
            item.put("position", readString(row, "position", "role"));
            Boolean active = readBoolean(row, "is_active", "active");
            item.put("active", active == null ? true : active);
            normalized.add(item);
        }
        return normalized;
    }

    public void createUser(String tenantId, String userId, String password, String position, boolean active) {
        String passwordHash = passwords.hash(password);
        java.util.UUID workspaceId = null;
        try {
            var ctx = com.example.BACKEND.tenancy.TenantContextHolder.get();
            if (ctx != null) workspaceId = ctx.tenantId();
        } catch (Exception ignored) {}

        if (workspaceId != null) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO public.app_users (tenant_id, user_id, password_hash, position, is_active, workspace_id) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        tenantId, userId, passwordHash, position, active, workspaceId);
                return;
            } catch (Exception ignored) {
                // workspace_id column may not exist pre Phase B migration
            }
        }
        jdbcTemplate.update(
                "INSERT INTO public.app_users (tenant_id, user_id, password_hash, position, is_active) "
                        + "VALUES (?, ?, ?, ?, ?)",
                tenantId, userId, passwordHash, position, active);
    }

    public void updateRole(String tenantId, String userId, String role) {
        String sql = "UPDATE public.app_users SET position = ? WHERE LOWER(tenant_id)=LOWER(?) AND LOWER(user_id)=LOWER(?)";
        int updated = jdbcTemplate.update(sql, role, tenantId, userId);
        if (updated == 0) throw new IllegalArgumentException("User not found");
    }

    public void updateStatus(String tenantId, String userId, boolean active) {
        String sql = "UPDATE public.app_users SET is_active = ? WHERE LOWER(tenant_id)=LOWER(?) AND LOWER(user_id)=LOWER(?)";
        int updated = jdbcTemplate.update(sql, active, tenantId, userId);
        if (updated == 0) throw new IllegalArgumentException("User not found");
    }

    public void deleteUser(String tenantId, String userId) {
        String sql = "DELETE FROM public.app_users WHERE LOWER(tenant_id)=LOWER(?) AND LOWER(user_id)=LOWER(?)";
        int deleted = jdbcTemplate.update(sql, tenantId, userId);
        if (deleted == 0) throw new IllegalArgumentException("User not found");
    }

    public List<String> listTablesForSchema(String schemaName) {
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? ORDER BY table_name";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, schemaName);
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = asString(row.get("table_name"));
            if (name != null) names.add(name);
        }
        return names;
    }

    private String inferSchemaFromTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return "";
        return tenantId.trim();
    }

    private String readString(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                    String v = String.valueOf(e.getValue()).trim();
                    if (!v.isEmpty()) return v;
                }
            }
        }
        return null;
    }

    private Boolean readBoolean(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key) && e.getValue() != null) {
                    Object v = e.getValue();
                    if (v instanceof Boolean b) return b;
                    return Boolean.parseBoolean(String.valueOf(v));
                }
            }
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
