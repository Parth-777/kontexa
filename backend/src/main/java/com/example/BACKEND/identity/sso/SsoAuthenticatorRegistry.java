package com.example.BACKEND.identity.sso;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SsoAuthenticatorRegistry {

    private final List<IdentityAuthenticator> authenticators;

    public SsoAuthenticatorRegistry(List<IdentityAuthenticator> authenticators) {
        this.authenticators = authenticators;
    }

    public IdentityAuthenticator resolve(String authProvider) {
        return authenticators.stream()
                .filter(a -> a.supports(authProvider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No authenticator for provider: " + authProvider));
    }
}
