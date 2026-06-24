package com.example.BACKEND.identity.password;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Identity;
import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.auth.PasswordService;
import com.example.BACKEND.identity.auth.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetService {

    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final TokenHasher tokenHasher;
    private final PasswordService passwords;
    private final AuditService audit;

    private final int resetTtlHours;
    private final String frontendBaseUrl;

    public PasswordResetService(
            JdbcTemplate jdbc,
            IdentityRepository identities,
            TokenHasher tokenHasher,
            PasswordService passwords,
            AuditService audit,
            @Value("${kontexa.auth.password-reset.ttl-hours:24}") int resetTtlHours,
            @Value("${kontexa.auth.frontend-base-url:http://localhost:3000}") String frontendBaseUrl
    ) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.tokenHasher = tokenHasher;
        this.passwords = passwords;
        this.audit = audit;
        this.resetTtlHours = resetTtlHours;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public boolean isEnabled() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='password_reset_tokens'",
                Integer.class);
        return n != null && n > 0;
    }

    public Optional<Map<String, String>> requestReset(String email, String ipAddress, String userAgent) {
        if (!isEnabled()) return Optional.empty();

        Optional<Identity> identityOpt = identities.findIdentityByEmailAcrossWorkspaces(email.trim());
        if (identityOpt.isEmpty()) {
            // Do not reveal whether email exists
            return Optional.of(Map.of("message", "If the account exists, a reset link has been generated."));
        }

        Identity identity = identityOpt.get();
        String rawToken = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expires = LocalDateTime.now().plusHours(resetTtlHours);

        jdbc.update("""
                INSERT INTO password_reset_tokens (id, identity_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                UUID.randomUUID(), identity.id(), tokenHasher.hash(rawToken), expires);

        audit.log(identity.workspaceId(), identity.id(), AuditService.PASSWORD_RESET,
                "reset_requested email=" + identity.email(), ipAddress, userAgent);

        return Optional.of(Map.of(
                "message", "If the account exists, a reset link has been generated.",
                "resetLink", frontendBaseUrl + "/reset-password?token=" + rawToken
        ));
    }

    @Transactional
    public void confirmReset(String rawToken, String newPassword, String ipAddress, String userAgent) {
        if (!isEnabled()) throw new IllegalStateException("password_reset_tokens table not found");

        var rows = jdbc.queryForList("""
                SELECT id, identity_id FROM password_reset_tokens
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
                LIMIT 1
                """, tokenHasher.hash(rawToken.trim()));

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Reset token is invalid or expired");
        }

        UUID tokenId = UUID.fromString(String.valueOf(rows.getFirst().get("id")));
        UUID identityId = UUID.fromString(String.valueOf(rows.getFirst().get("identity_id")));

        jdbc.update("UPDATE identities SET password_hash = ? WHERE id = ?",
                passwords.hash(newPassword), identityId);
        jdbc.update("UPDATE password_reset_tokens SET used_at = NOW() WHERE id = ?", tokenId);

        Optional<Identity> identityOpt = identities.findIdentityById(identityId);
        identityOpt.ifPresent(id ->
                audit.log(id.workspaceId(), identityId, AuditService.PASSWORD_RESET,
                        "password_changed email=" + id.email(), ipAddress, userAgent));
    }
}
