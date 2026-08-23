package com.example.BACKEND.identity.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordServiceTest {

    private final PasswordService passwords = new PasswordService();

    @Test
    void hash_neverPersistsPlaintext() {
        String plain = "CorrectHorseBatteryStaple!";
        String hashed = passwords.hash(plain);
        assertNotEquals(plain, hashed);
        assertTrue(passwords.isBcryptHash(hashed));
        assertTrue(hashed.startsWith("$2a$") || hashed.startsWith("$2b$") || hashed.startsWith("$2y$"));
    }

    @Test
    void matches_succeedsForBcryptHash() {
        String plain = "s3cret-login-password";
        String hashed = passwords.hash(plain);
        assertTrue(passwords.matches(plain, hashed));
        assertTrue(passwords.check(plain, hashed).matched());
        assertFalse(passwords.check(plain, hashed).requiresRehash());
    }

    @Test
    void matches_failsForIncorrectPassword() {
        String hashed = passwords.hash("right-password");
        assertFalse(passwords.matches("wrong-password", hashed));
        assertFalse(passwords.check("wrong-password", hashed).matched());
    }

    @Test
    void matches_rejectsPlaintextStoredValue() {
        // BCrypt-only API: plaintext rows do not match via matches()
        assertFalse(passwords.matches("legacy", "legacy"));
    }

    @Test
    void check_upgradesLegacyPlaintextOnce() {
        PasswordService.LoginPasswordCheck check = passwords.check("legacy-pass", "legacy-pass");
        assertTrue(check.matched());
        assertTrue(check.requiresRehash());

        String upgraded = passwords.hash("legacy-pass");
        assertTrue(passwords.isBcryptHash(upgraded));
        assertTrue(passwords.matches("legacy-pass", upgraded));
        assertFalse(passwords.matches("wrong", upgraded));
    }

    @Test
    void check_rejectsWrongLegacyPlaintext() {
        assertFalse(passwords.check("nope", "legacy-pass").matched());
    }
}
