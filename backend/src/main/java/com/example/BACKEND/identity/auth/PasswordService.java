package com.example.BACKEND.identity.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * BCrypt password hashing and verification.
 * New passwords are always stored as BCrypt. Login uses {@link #check} which
 * verifies BCrypt via {@link BCryptPasswordEncoder#matches}; legacy plaintext
 * rows are accepted only so the caller can immediately rehash and persist BCrypt.
 */
@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String plainPassword) {
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new IllegalArgumentException("password required");
        }
        return encoder.encode(plainPassword);
    }

    public boolean isBcryptHash(String stored) {
        return stored != null
                && (stored.startsWith("$2a$")
                || stored.startsWith("$2b$")
                || stored.startsWith("$2y$"));
    }

    public boolean needsRehash(String stored) {
        return !isBcryptHash(stored);
    }

    /**
     * BCrypt-only verification. Never uses string equality.
     * Legacy plaintext stored values always return false — use {@link #check} at login.
     */
    public boolean matches(String plainPassword, String stored) {
        if (plainPassword == null || stored == null || stored.isBlank()) return false;
        if (!isBcryptHash(stored)) return false;
        return encoder.matches(plainPassword, stored);
    }

    /**
     * Login verification. BCrypt via encoder.matches; non-BCrypt rows may match once
     * so the caller can rehash with {@link #hash} and stop storing plaintext.
     */
    public LoginPasswordCheck check(String plainPassword, String stored) {
        if (plainPassword == null || stored == null || stored.isBlank()) {
            return LoginPasswordCheck.failed();
        }
        if (isBcryptHash(stored)) {
            boolean ok = encoder.matches(plainPassword, stored);
            return ok ? LoginPasswordCheck.ok(false) : LoginPasswordCheck.failed();
        }
        boolean ok = MessageDigest.isEqual(
                plainPassword.getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
        return ok ? LoginPasswordCheck.ok(true) : LoginPasswordCheck.failed();
    }

    public record LoginPasswordCheck(boolean matched, boolean requiresRehash) {
        static LoginPasswordCheck failed() {
            return new LoginPasswordCheck(false, false);
        }

        static LoginPasswordCheck ok(boolean requiresRehash) {
            return new LoginPasswordCheck(true, requiresRehash);
        }
    }
}
