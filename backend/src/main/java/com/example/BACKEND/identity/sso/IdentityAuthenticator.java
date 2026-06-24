package com.example.BACKEND.identity.sso;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.AuthResult;

import java.util.Optional;

/**
 * Pluggable authentication provider contract.
 * Implementations: LOCAL_PASSWORD (active), OKTA, AZURE_AD, SAML, OIDC (future).
 */
public interface IdentityAuthenticator {

    String providerType();

    boolean supports(String authProvider);

    Optional<AuthResult> authenticate(Credentials credentials);

    record Credentials(
            String emailOrUserId,
            String password,
            String workspaceSlug,
            String idpToken
    ) {}
}
