package com.example.BACKEND.identity.auth;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.*;
import com.example.BACKEND.tenant.TenantAuthService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.user.UserAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Transitional bridge to legacy user_credentials and app_users.
 * Used only when canonical identity lookup fails.
 * Will be removed once migration is validated.
 */
@Component
public class LegacyAuthBridge {

    private static final Logger log = LoggerFactory.getLogger(LegacyAuthBridge.class);

    private final TenantAuthService tenantAuth;
    private final UserAccessService userAuth;
    private final TenantCloudConnectionService cloudConnections;

    public LegacyAuthBridge(
            TenantAuthService tenantAuth,
            UserAccessService userAuth,
            TenantCloudConnectionService cloudConnections
    ) {
        this.tenantAuth = tenantAuth;
        this.userAuth = userAuth;
        this.cloudConnections = cloudConnections;
    }

    public Optional<AuthResult> authenticate(String userId, String password) {
        Optional<AuthResult> tenant = authenticateTenant(userId, password);
        if (tenant.isPresent()) return tenant;
        return authenticateUser(userId, password);
    }

    private Optional<AuthResult> authenticateTenant(String userId, String password) {
        try {
            return tenantAuth.authenticate(userId, password)
                    .map(auth -> {
                        log.debug("[legacy-bridge] tenant auth for {}", userId);
                        return new AuthResult(
                                null, null,
                                auth.tenantId(),
                                userId,
                                auth.userId(),
                                Role.OWNER,
                                resolveConnector(auth.tenantId()),
                                AuthSource.LEGACY_TENANT
                        );
                    });
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<AuthResult> authenticateUser(String userId, String password) {
        try {
            return userAuth.authenticate(userId, password)
                    .map(r -> {
                        log.debug("[legacy-bridge] user auth for {}", userId);
                        return new AuthResult(
                                null, null,
                                r.tenantId(),
                                userId,
                                r.userId(),
                                Role.fromLegacyPosition(r.position()),
                                resolveConnector(r.tenantId()),
                                AuthSource.LEGACY_USER
                        );
                    });
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String resolveConnector(String tenantId) {
        if (cloudConnections.getProvider(tenantId).isBlank()) return null;
        return cloudConnections.getBigQueryConfig(tenantId)
                .map(c -> "{\"provider\":\"bigquery\",\"projectId\":\"" + c.projectId() + "\"}")
                .orElse(null);
    }
}
