package com.example.BACKEND.identity.session;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Identity;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Workspace;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository sessions;
    private final IdentityRepository identities;
    private final TokenHasher tokenHasher;

    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public SessionService(
            SessionRepository sessions,
            IdentityRepository identities,
            TokenHasher tokenHasher,
            @Value("${kontexa.auth.session.access-ttl-minutes:60}") int accessTtlMinutes,
            @Value("${kontexa.auth.session.refresh-ttl-days:14}") int refreshTtlDays
    ) {
        this.sessions = sessions;
        this.identities = identities;
        this.tokenHasher = tokenHasher;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    public boolean isEnabled() {
        return sessions.sessionsTableExists();
    }

    public SessionTokens createSession(
            Identity identity,
            UUID workspaceId,
            String deviceLabel,
            String userAgent,
            String ipAddress
    ) {
        if (!isEnabled()) {
            throw new IllegalStateException("auth_sessions table not found — run enterprise_auth_migration.sql");
        }

        String accessRaw  = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String refreshRaw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");

        LocalDateTime accessExpires  = LocalDateTime.now().plusMinutes(accessTtlMinutes);
        LocalDateTime refreshExpires = LocalDateTime.now().plusDays(refreshTtlDays);

        UUID sessionId = sessions.createSession(
                identity.id(),
                workspaceId,
                tokenHasher.hash(accessRaw),
                tokenHasher.hash(refreshRaw),
                deviceLabel,
                userAgent,
                ipAddress,
                accessExpires,
                refreshExpires
        );

        return new SessionTokens(sessionId, accessRaw, refreshRaw, accessExpires, refreshExpires);
    }

    public Optional<AuthContext> resolveAccessToken(String rawAccessToken) {
        if (!isEnabled() || rawAccessToken == null || rawAccessToken.isBlank()) {
            return Optional.empty();
        }

        return sessions.findByAccessTokenHash(tokenHasher.hash(rawAccessToken.trim()))
                .filter(s -> s.revokedAt() == null)
                .filter(s -> s.accessExpiresAt().isAfter(LocalDateTime.now()))
                .flatMap(this::toAuthContext);
    }

    public Optional<SessionTokens> refresh(String rawRefreshToken) {
        if (!isEnabled() || rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }

        Optional<AuthSessionRecord> sessionOpt = sessions.findByRefreshTokenHash(tokenHasher.hash(rawRefreshToken.trim()))
                .filter(s -> s.revokedAt() == null)
                .filter(s -> s.refreshExpiresAt().isAfter(LocalDateTime.now()));

        if (sessionOpt.isEmpty()) return Optional.empty();

        AuthSessionRecord session = sessionOpt.get();
        Optional<Identity> identityOpt = identities.findIdentityById(session.identityId());
        if (identityOpt.isEmpty()) return Optional.empty();

        String newAccessRaw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime accessExpires = LocalDateTime.now().plusMinutes(accessTtlMinutes);

        sessions.updateActiveWorkspace(
                session.id(),
                session.activeWorkspaceId(),
                tokenHasher.hash(newAccessRaw),
                accessExpires
        );
        sessions.touchSession(session.id());

        return Optional.of(new SessionTokens(
                session.id(),
                newAccessRaw,
                rawRefreshToken.trim(),
                accessExpires,
                session.refreshExpiresAt()
        ));
    }

    public Optional<SessionTokens> switchWorkspace(UUID sessionId, UUID newWorkspaceId) {
        if (!isEnabled()) return Optional.empty();

        Optional<AuthSessionRecord> existing = sessions.findBySessionId(sessionId);
        if (existing.isEmpty()) return Optional.empty();

        AuthSessionRecord session = existing.get();
        String newAccessRaw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime accessExpires = LocalDateTime.now().plusMinutes(accessTtlMinutes);

        sessions.updateActiveWorkspace(sessionId, newWorkspaceId, tokenHasher.hash(newAccessRaw), accessExpires);

        return Optional.of(new SessionTokens(
                sessionId, newAccessRaw, null, accessExpires, session.refreshExpiresAt()));
    }

    public void revoke(String rawAccessToken) {
        if (!isEnabled() || rawAccessToken == null) return;
        sessions.findByAccessTokenHash(tokenHasher.hash(rawAccessToken.trim()))
                .ifPresent(s -> sessions.revokeSession(s.id()));
    }

    private Optional<AuthContext> toAuthContext(AuthSessionRecord session) {
        Optional<Identity> identityOpt = identities.findIdentityById(session.identityId());
        if (identityOpt.isEmpty()) return Optional.empty();

        Identity identity = identityOpt.get();
        Optional<Workspace> workspaceOpt = identities.findWorkspaceById(session.activeWorkspaceId());
        String slug = workspaceOpt.map(Workspace::slug).orElse("");
        String role = identities.findRoleForIdentityInWorkspace(session.activeWorkspaceId(), identity.id())
                .orElse("VIEWER");

        sessions.touchSession(session.id());

        return Optional.of(new AuthContext(
                session.id(),
                identity.id(),
                session.activeWorkspaceId(),
                slug,
                identity.email(),
                identity.displayName() != null ? identity.displayName() : identity.email(),
                role,
                "IDENTITY_V2"
        ));
    }
}
