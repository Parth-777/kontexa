package com.example.BACKEND.tenant;

import com.example.BACKEND.identity.auth.PasswordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Minimal tenant login service backed by a manually managed credentials table.
 *
 * Defaults:
 *  - schema: public
 *  - table: user_credentials
 *  - user id column: user_id
 *
 * Passwords are verified via {@link PasswordService} (BCrypt). Legacy plaintext
 * rows are upgraded to BCrypt on successful login.
 */
@Service
public class TenantAuthService {

    public record TenantAuthResult(String tenantId, String userId, String tenantSchema, String cloudDbLink) {}

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwords;
    private final String schemaName;
    private final String tableName;
    private final String userIdColumn;
    private final String cloudLinkColumn;

    public TenantAuthService(
            JdbcTemplate jdbcTemplate,
            PasswordService passwords,
            @Value("${auth.tenant.schema:public}") String schemaName,
            @Value("${auth.tenant.table:user_credentials}") String tableName,
            @Value("${auth.tenant.user-column:user_id}") String userIdColumn,
            @Value("${auth.tenant.cloud-link-column:cloud_db_link}") String cloudLinkColumn
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwords = passwords;
        this.schemaName = validateIdentifier(schemaName, "auth.tenant.schema");
        this.tableName = validateIdentifier(tableName, "auth.tenant.table");
        this.userIdColumn = validateIdentifier(userIdColumn, "auth.tenant.user-column");
        this.cloudLinkColumn = validateIdentifier(cloudLinkColumn, "auth.tenant.cloud-link-column");
    }

    public Optional<TenantAuthResult> authenticate(String userId, String password) {
        String normalizedUserId = normalize(userId);
        String normalizedPassword = normalize(password);
        if (normalizedUserId == null || normalizedPassword == null) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM " + schemaName + "." + tableName
                + " WHERE LOWER(" + userIdColumn + ") = LOWER(?) LIMIT 1";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, normalizedUserId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.getFirst();
        String storedPassword = readFirstPresentValue(row, List.of(
                "password_hash",
                "password",
                "psw",
                "passwd"
        ));

        PasswordService.LoginPasswordCheck check = passwords.check(normalizedPassword, storedPassword);
        if (!check.matched()) {
            return Optional.empty();
        }
        if (check.requiresRehash()) {
            upgradeStoredPassword(row, normalizedUserId, passwords.hash(normalizedPassword));
        }

        String resolvedUserId = readFirstPresentValue(row, List.of(
                userIdColumn,
                "user_id",
                "userid",
                "userId"
        ));

        String tenantSchema = readFirstPresentValue(row, List.of(
                "tenant_schema",
                "schema_name",
                "client_id",
                "tenant_id",
                "tenant",
                "client"
        ));
        String tenantId = readFirstPresentValue(row, List.of(
                "tenant_id",
                "tenant",
                "client_id",
                "client",
                "tenant_schema",
                "schema_name"
        ));

        String cloudDbLink = readFirstPresentValue(row, List.of(
                cloudLinkColumn,
                "cloud_db_link",
                "database_link",
                "db_link",
                "warehouse_link"
        ));

        String finalUserId = resolvedUserId != null ? resolvedUserId : normalizedUserId;
        // Generic multi-tenant rule: tenant id and schema should resolve to the same logical value.
        // Prefer explicit tenant_id from DB row; fallback to login id.
        String finalTenantId = tenantId != null ? tenantId : finalUserId;
        String finalTenantSchema = tenantSchema != null ? tenantSchema : inferSchemaFromTenantLogin(finalTenantId);

        return Optional.of(new TenantAuthResult(finalTenantId, finalUserId, finalTenantSchema, cloudDbLink));
    }

    public Optional<String> getCloudDbLink(String tenantId) {
        String normalizedTenantId = normalize(tenantId);
        if (normalizedTenantId == null) {
            return Optional.empty();
        }

        String sql = "SELECT " + cloudLinkColumn + " FROM " + schemaName + "." + tableName
                + " WHERE LOWER(" + userIdColumn + ") = LOWER(?) LIMIT 1";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, normalizedTenantId);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Object value = rows.getFirst().values().stream().findFirst().orElse(null);
            if (value == null) {
                return Optional.of("");
            }
            return Optional.of(String.valueOf(value).trim());
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "Cloud DB link column not found. Add column '" + cloudLinkColumn + "' in "
                            + schemaName + "." + tableName,
                    ex
            );
        }
    }

    public void updateCloudDbLink(String tenantId, String cloudDbLink) {
        String normalizedTenantId = normalize(tenantId);
        String normalizedLink = normalize(cloudDbLink);
        if (normalizedTenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (normalizedLink == null) {
            throw new IllegalArgumentException("cloudDbLink is required");
        }

        String updateSql = "UPDATE " + schemaName + "." + tableName
                + " SET " + cloudLinkColumn + " = ?"
                + " WHERE LOWER(" + userIdColumn + ") = LOWER(?)";

        try {
            int updated = jdbcTemplate.update(updateSql, normalizedLink, normalizedTenantId);
            if (updated == 0) {
                throw new IllegalArgumentException("Tenant not found for tenantId: " + normalizedTenantId);
            }
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "Cloud DB link column not found. Add column '" + cloudLinkColumn + "' in "
                            + schemaName + "." + tableName,
                    ex
            );
        }
    }

    private void upgradeStoredPassword(Map<String, Object> row, String userId, String bcryptHash) {
        String column = resolvePasswordColumn(row);
        if (column == null) return;
        try {
            jdbcTemplate.update(
                    "UPDATE " + schemaName + "." + tableName
                            + " SET " + column + " = ?"
                            + " WHERE LOWER(" + userIdColumn + ") = LOWER(?)",
                    bcryptHash, userId);
        } catch (DataAccessException ignored) {
            // Best-effort upgrade; login already succeeded
        }
    }

    private String resolvePasswordColumn(Map<String, Object> row) {
        for (String candidate : List.of("password_hash", "password", "psw", "passwd")) {
            for (String key : row.keySet()) {
                if (key.equalsIgnoreCase(candidate)) {
                    return validateIdentifier(key, "password-column");
                }
            }
        }
        return null;
    }

    private String inferSchemaFromTenantLogin(String tenantLoginId) {
        if (tenantLoginId == null || tenantLoginId.isBlank()) return "";
        return tenantLoginId.trim();
    }

    private String readFirstPresentValue(Map<String, Object> row, List<String> candidates) {
        for (String candidate : candidates) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(candidate) && entry.getValue() != null) {
                    String value = String.valueOf(entry.getValue()).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String validateIdentifier(String value, String propertyName) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier for " + propertyName + ": " + value);
        }
        return value;
    }
}
