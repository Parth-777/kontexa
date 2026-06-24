package com.example.BACKEND.identity.sso;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.AuthResult;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Placeholder for enterprise SSO providers (Okta, Azure AD, Google Workspace SAML, OIDC).
 * Returns empty until IdP integration is configured per workspace.
 */
@Component
public class ExternalSsoAuthenticator implements IdentityAuthenticator {

    private static final Set<String> SSO_PROVIDERS = Set.of(
            "OKTA", "AZURE_AD", "GOOGLE_WORKSPACE", "SAML", "OIDC"
    );

    @Override
    public String providerType() {
        return "EXTERNAL_SSO";
    }

    @Override
    public boolean supports(String authProvider) {
        return authProvider != null && SSO_PROVIDERS.contains(authProvider.toUpperCase());
    }

    @Override
    public Optional<AuthResult> authenticate(Credentials credentials) {
        // Future: validate idpToken against workspace sso_config
        return Optional.empty();
    }
}
