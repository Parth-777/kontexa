package com.example.BACKEND.identity.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Password hashing and verification.
 * Supports BCrypt (new) and plain-text (legacy migrated rows) during transition.
 */
@Service
public class PasswordService {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String plainPassword) {
        return encoder.encode(plainPassword);
    }

    public boolean matches(String plainPassword, String stored) {
        if (plainPassword == null || stored == null) return false;
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return encoder.matches(plainPassword, stored);
        }
        // Legacy plain-text during migration transition
        return stored.equals(plainPassword);
    }
}
