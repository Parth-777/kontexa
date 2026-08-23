package com.example.BACKEND.user;

import com.example.BACKEND.identity.auth.PasswordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccessServicePasswordTest {

    @Mock
    private JdbcTemplate jdbc;

    private final PasswordService passwords = new PasswordService();

    @Test
    void createUser_persistsBcryptHashNotPlaintext() {
        UserAccessService service = new UserAccessService(jdbc, passwords);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        service.createUser("acme", "bob", "plain-password", "Analyst", true);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO public.app_users"),
                eq("acme"), eq("bob"), captor.capture(), eq("Analyst"), eq(true));
        String stored = String.valueOf(captor.getValue());
        assertTrue(passwords.isBcryptHash(stored));
        assertTrue(!stored.equals("plain-password"));
        assertTrue(passwords.matches("plain-password", stored));
    }

    @Test
    void authenticate_bcryptSucceedsAndWrongPasswordFails() {
        UserAccessService service = new UserAccessService(jdbc, passwords);
        String hash = passwords.hash("right");
        when(jdbc.queryForList(anyString(), eq("bob"))).thenReturn(List.of(Map.of(
                "user_id", "bob",
                "tenant_id", "acme",
                "password_hash", hash,
                "position", "Viewer",
                "is_active", true
        )));

        Optional<UserAccessService.UserLoginResult> ok = service.authenticate("bob", "right");
        assertTrue(ok.isPresent());
        assertEquals("acme", ok.get().tenantId());

        assertTrue(service.authenticate("bob", "wrong").isEmpty());
    }

    @Test
    void authenticate_legacyPlaintext_upgradesOnSuccess() {
        UserAccessService service = new UserAccessService(jdbc, passwords);
        when(jdbc.queryForList(anyString(), eq("bob"))).thenReturn(List.of(Map.of(
                "user_id", "bob",
                "tenant_id", "acme",
                "password_hash", "legacy",
                "position", "Viewer",
                "is_active", true
        )));
        doReturn(1).when(jdbc).update(anyString(), any(Object.class), any(Object.class));

        assertTrue(service.authenticate("bob", "legacy").isPresent());

        ArgumentCaptor<Object> hashCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("UPDATE public.app_users SET password_hash"), hashCaptor.capture(), eq("bob"));
        assertTrue(passwords.isBcryptHash(String.valueOf(hashCaptor.getValue())));
    }
}
