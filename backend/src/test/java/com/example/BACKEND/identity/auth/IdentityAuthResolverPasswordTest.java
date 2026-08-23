package com.example.BACKEND.identity.auth;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.AuthResult;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.AuthSource;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Identity;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityAuthResolverPasswordTest {

    @Mock
    private IdentityRepository repo;

    @Spy
    private PasswordService passwords = new PasswordService();

    @InjectMocks
    private IdentityAuthResolver resolver;

    @Test
    void login_withBcrypt_succeedsWithoutRehash() {
        UUID identityId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String hash = passwords.hash("good-pass");
        Identity identity = new Identity(
                identityId, workspaceId, "a@acme.com", "Ada", "LOCAL_PASSWORD",
                hash, "ACTIVE", null, null, null, null);

        when(repo.identityTablesExist()).thenReturn(true);
        when(repo.findLoginByLegacyUserId("a@acme.com")).thenReturn(Optional.empty());
        when(repo.findFirstMembershipLogin("a@acme.com")).thenReturn(Optional.of(
                new IdentityAuthResolver.ResolvedLogin(identity, workspaceId, "acme", Role.OWNER)));

        Optional<AuthResult> result = resolver.resolve("a@acme.com", "good-pass", null);
        assertTrue(result.isPresent());
        assertEquals(AuthSource.IDENTITY_V2, result.get().authSource());
        verify(repo, never()).updatePasswordHash(any(), any());
        verify(repo).updateLastLogin(identityId);
    }

    @Test
    void login_withWrongPassword_fails() {
        UUID identityId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String hash = passwords.hash("good-pass");
        Identity identity = new Identity(
                identityId, workspaceId, "a@acme.com", "Ada", "LOCAL_PASSWORD",
                hash, "ACTIVE", null, null, null, null);

        when(repo.identityTablesExist()).thenReturn(true);
        when(repo.findLoginByLegacyUserId("a@acme.com")).thenReturn(Optional.empty());
        when(repo.findFirstMembershipLogin("a@acme.com")).thenReturn(Optional.of(
                new IdentityAuthResolver.ResolvedLogin(identity, workspaceId, "acme", Role.OWNER)));

        assertTrue(resolver.resolve("a@acme.com", "bad-pass", null).isEmpty());
        verify(repo, never()).updateLastLogin(any());
    }

    @Test
    void login_withLegacyPlaintext_upgradesToBcrypt() {
        UUID identityId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Identity identity = new Identity(
                identityId, workspaceId, "a@acme.com", "Ada", "LOCAL_PASSWORD",
                "plaintext-secret", "ACTIVE", null, null, null, null);

        when(repo.identityTablesExist()).thenReturn(true);
        when(repo.findLoginByLegacyUserId("a@acme.com")).thenReturn(Optional.empty());
        when(repo.findFirstMembershipLogin("a@acme.com")).thenReturn(Optional.of(
                new IdentityAuthResolver.ResolvedLogin(identity, workspaceId, "acme", Role.OWNER)));

        Optional<AuthResult> result = resolver.resolve("a@acme.com", "plaintext-secret", null);
        assertTrue(result.isPresent());

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(repo).updatePasswordHash(eq(identityId), hashCaptor.capture());
        assertTrue(passwords.isBcryptHash(hashCaptor.getValue()));
        assertTrue(passwords.matches("plaintext-secret", hashCaptor.getValue()));
        assertTrue(!hashCaptor.getValue().equals("plaintext-secret"));
    }
}
