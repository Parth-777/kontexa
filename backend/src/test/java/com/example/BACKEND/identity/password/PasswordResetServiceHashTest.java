package com.example.BACKEND.identity.password;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Identity;
import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.auth.PasswordService;
import com.example.BACKEND.identity.auth.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceHashTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private IdentityRepository identities;
    @Mock
    private AuditService audit;

    private final PasswordService passwords = new PasswordService();
    private final TokenHasher tokenHasher = new TokenHasher();
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(jdbc, identities, tokenHasher, passwords, audit, 24);
    }

    @Test
    void confirmReset_persistsBcryptHash() {
        UUID identityId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String rawToken = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(tokenHasher.hash(rawToken))))
                .thenReturn(List.of(Map.of("id", tokenId.toString(), "identity_id", identityId.toString())));
        when(identities.findIdentityById(identityId)).thenReturn(Optional.of(
                new Identity(identityId, UUID.randomUUID(), "a@acme.com", "Ada", "LOCAL_PASSWORD",
                        null, "ACTIVE", null, null, null, null)));
        when(jdbc.update(anyString(), any(Object.class), any(Object.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(1);

        service.confirmReset(rawToken, "NewSecurePass1!", "127.0.0.1", "test");

        ArgumentCaptor<Object> hashCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("UPDATE identities SET password_hash"), hashCaptor.capture(), eq(identityId));
        String stored = String.valueOf(hashCaptor.getValue());
        assertTrue(passwords.isBcryptHash(stored));
        assertTrue(passwords.matches("NewSecurePass1!", stored));
        assertTrue(!stored.equals("NewSecurePass1!"));
    }
}
