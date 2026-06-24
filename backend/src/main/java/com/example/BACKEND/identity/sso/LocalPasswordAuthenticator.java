package com.example.BACKEND.identity.sso;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.AuthResult;
import com.example.BACKEND.identity.auth.LegacyAuthBridge;
import com.example.BACKEND.identity.auth.IdentityAuthResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LocalPasswordAuthenticator implements IdentityAuthenticator {

    private final IdentityAuthResolver identityResolver;
    private final LegacyAuthBridge legacyBridge;

    public LocalPasswordAuthenticator(IdentityAuthResolver identityResolver, LegacyAuthBridge legacyBridge) {
        this.identityResolver = identityResolver;
        this.legacyBridge = legacyBridge;
    }

    @Override
    public String providerType() {
        return "LOCAL_PASSWORD";
    }

    @Override
    public boolean supports(String authProvider) {
        return authProvider == null
                || authProvider.isBlank()
                || "LOCAL_PASSWORD".equalsIgnoreCase(authProvider);
    }

    @Override
    public Optional<AuthResult> authenticate(Credentials credentials) {
        Optional<AuthResult> v2 = identityResolver.resolve(
                credentials.emailOrUserId(),
                credentials.password(),
                credentials.workspaceSlug()
        );
        if (v2.isPresent()) return v2;

        return legacyBridge.authenticate(credentials.emailOrUserId(), credentials.password());
    }
}
