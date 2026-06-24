package com.example.BACKEND.identity.auth;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.AuthResult;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Identity;
import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.session.SessionService;
import com.example.BACKEND.identity.session.SessionTokens;
import com.example.BACKEND.identity.sso.IdentityAuthenticator;
import com.example.BACKEND.identity.sso.SsoAuthenticatorRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication orchestrator — validates credentials and issues sessions.
 * Does NOT perform authorization (role checks).
 */
@Service
public class AuthenticationService {

    private final SsoAuthenticatorRegistry ssoRegistry;
    private final SessionService sessions;
    private final IdentityRepository identities;
    private final IdentityAuthResolver identityResolver;
    private final AuditService audit;

    public AuthenticationService(
            SsoAuthenticatorRegistry ssoRegistry,
            SessionService sessions,
            IdentityRepository identities,
            IdentityAuthResolver identityResolver,
            AuditService audit
    ) {
        this.ssoRegistry = ssoRegistry;
        this.sessions = sessions;
        this.identities = identities;
        this.identityResolver = identityResolver;
        this.audit = audit;
    }

    public Optional<Map<String, Object>> login(
            String email,
            String password,
            String workspaceSlug,
            String authProvider,
            String deviceLabel,
            String userAgent,
            String ipAddress
    ) {
        IdentityAuthenticator authenticator = ssoRegistry.resolve(authProvider);
        Optional<AuthResult> authOpt = authenticator.authenticate(
                new IdentityAuthenticator.Credentials(email, password, workspaceSlug, null));

        if (authOpt.isEmpty()) {
            audit.log(null, null, AuditService.LOGIN_FAILED,
                    "email=" + email + " workspace=" + workspaceSlug, ipAddress, userAgent);
            return Optional.empty();
        }

        AuthResult auth = authOpt.get();
        Map<String, Object> response = buildAuthResponse(auth);

        if (sessions.isEnabled() && auth.identityId() != null && auth.workspaceId() != null) {
            Optional<Identity> identityOpt = identities.findIdentityById(auth.identityId());
            if (identityOpt.isPresent()) {
                SessionTokens tokens = sessions.createSession(
                        identityOpt.get(), auth.workspaceId(), deviceLabel, userAgent, ipAddress);
                response.put("accessToken", tokens.accessToken());
                response.put("refreshToken", tokens.refreshToken());
                response.put("accessExpiresAt", tokens.accessExpiresAt().toString());
                response.put("refreshExpiresAt", tokens.refreshExpiresAt().toString());
            }
        }

        List<Map<String, Object>> workspaces = identityResolver.listWorkspacesForEmail(email);
        response.put("availableWorkspaces", workspaces);

        audit.log(auth.workspaceId(), auth.identityId(), AuditService.LOGIN,
                "email=" + auth.email() + " source=" + auth.authSource(), ipAddress, userAgent);

        return Optional.of(response);
    }

    public Optional<Map<String, Object>> refresh(String refreshToken) {
        return sessions.refresh(refreshToken).map(tokens -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accessToken", tokens.accessToken());
            m.put("refreshToken", tokens.refreshToken());
            m.put("accessExpiresAt", tokens.accessExpiresAt().toString());
            m.put("refreshExpiresAt", tokens.refreshExpiresAt().toString());
            return m;
        });
    }

    public Optional<Map<String, Object>> switchWorkspace(
            UUID sessionId,
            UUID identityId,
            String workspaceSlug,
            String ipAddress,
            String userAgent
    ) {
        Optional<UUID> wsId = identities.findWorkspaceIdBySlug(workspaceSlug);
        if (wsId.isEmpty()) return Optional.empty();

        Optional<AuthResult> authOpt = identityResolver.resolveForWorkspace(identityId, wsId.get());
        if (authOpt.isEmpty()) return Optional.empty();

        Optional<com.example.BACKEND.identity.session.SessionTokens> tokensOpt =
                sessions.switchWorkspace(sessionId, wsId.get());

        if (tokensOpt.isEmpty()) return Optional.empty();

        Map<String, Object> response = buildAuthResponse(authOpt.get());
        var tokens = tokensOpt.get();
        response.put("accessToken", tokens.accessToken());
        response.put("accessExpiresAt", tokens.accessExpiresAt().toString());

        audit.log(wsId.get(), identityId, AuditService.WORKSPACE_SWITCH,
                "workspace=" + workspaceSlug, ipAddress, userAgent);

        return Optional.of(response);
    }

    public void logout(String accessToken, UUID workspaceId, UUID identityId,
                       String ipAddress, String userAgent) {
        sessions.revoke(accessToken);
        audit.log(workspaceId, identityId, AuditService.LOGOUT, null, ipAddress, userAgent);
    }

    private Map<String, Object> buildAuthResponse(AuthResult auth) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accountType", "WORKSPACE");
        response.put("identityId", auth.identityId() != null ? auth.identityId().toString() : "");
        response.put("workspaceId", auth.workspaceId() != null ? auth.workspaceId().toString() : "");
        response.put("workspaceSlug", auth.workspaceSlug() != null ? auth.workspaceSlug() : "");
        response.put("email", auth.email());
        response.put("displayName", auth.displayName());
        response.put("role", auth.role());
        response.put("authSource", auth.authSource());
        response.put("connectorReady", auth.connectorConfigJson() != null && !auth.connectorConfigJson().isBlank());
        // Backward compat
        response.put("tenantId", auth.workspaceSlug());
        response.put("userId", auth.email());
        response.put("tenantSchema", auth.workspaceSlug());
        response.put("cloudDbLink", auth.connectorConfigJson() != null ? auth.connectorConfigJson() : "");
        return response;
    }
}
