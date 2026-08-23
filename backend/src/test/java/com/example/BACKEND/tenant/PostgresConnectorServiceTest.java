package com.example.BACKEND.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresConnectorServiceTest {

    private final PostgresConnectorService connector = new PostgresConnectorService();

    @Test
    void jdbcUrlUsesConfiguredHostDatabaseAndSslMode() {
        assertEquals(
                "jdbc:postgresql://db.example.com:5432/analytics?sslmode=disable",
                PostgresConnectorService.buildJdbcUrl("db.example.com", 5432, "analytics", false));
        assertEquals(
                "jdbc:postgresql://db.example.com:5432/analytics?sslmode=require",
                PostgresConnectorService.buildJdbcUrl("db.example.com", 5432, "analytics", true));
    }

    @Test
    void defaultPortIs5432() {
        assertEquals(5432, PostgresConnectorService.normalizePort(null));
        assertEquals(5432, PostgresConnectorService.DEFAULT_PORT);
        assertEquals("public", PostgresConnectorService.DEFAULT_SCHEMA);
    }

    @Test
    void normalizePortRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> PostgresConnectorService.normalizePort(0));
        assertThrows(IllegalArgumentException.class, () -> PostgresConnectorService.normalizePort(70000));
    }

    @Test
    void quoteIdentEscapesQuotesAndRejectsBlankOrControlChars() {
        assertEquals("\"event_log\"", PostgresConnectorService.quoteIdent("event_log"));
        assertEquals("\"weird\"\"name\"", PostgresConnectorService.quoteIdent("weird\"name"));
        assertThrows(IllegalArgumentException.class, () -> PostgresConnectorService.quoteIdent(""));
        assertThrows(IllegalArgumentException.class, () -> PostgresConnectorService.quoteIdent("bad\nname"));
    }

    @Test
    void connectionLinkDoesNotIncludeCredentials() {
        String link = connector.buildConnectionLink("db.example.com", 5432, "analytics", "public", true);
        assertTrue(link.startsWith("postgresql://db.example.com:5432/analytics"));
        assertTrue(link.contains("schema=public"));
        assertTrue(link.contains("ssl=true"));
        assertTrue(!link.toLowerCase().contains("password"));
        assertTrue(!link.contains("@"));
    }

    @Test
    void testConnectionRejectsMissingRequiredFieldsWithoutOpeningJdbc() {
        IllegalArgumentException host = assertThrows(
                IllegalArgumentException.class,
                () -> connector.testConnection("", 5432, "analytics", "public", "user", "secret", false));
        assertTrue(host.getMessage().contains("host"));

        IllegalArgumentException database = assertThrows(
                IllegalArgumentException.class,
                () -> connector.testConnection("db.example.com", 5432, "", "public", "user", "secret", false));
        assertTrue(database.getMessage().contains("database"));

        IllegalArgumentException username = assertThrows(
                IllegalArgumentException.class,
                () -> connector.testConnection("db.example.com", 5432, "analytics", "public", "", "secret", false));
        assertTrue(username.getMessage().contains("username"));

        IllegalArgumentException password = assertThrows(
                IllegalArgumentException.class,
                () -> connector.testConnection("db.example.com", 5432, "analytics", "public", "user", "", false));
        assertTrue(password.getMessage().contains("password"));
    }

    @Test
    void hostMustBeHostnameOrIp() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> connector.testConnection("db.example.com/analytics", 5432, "analytics", "public", "user", "secret", false));
        assertTrue(ex.getMessage().toLowerCase().contains("host"));
    }
}
