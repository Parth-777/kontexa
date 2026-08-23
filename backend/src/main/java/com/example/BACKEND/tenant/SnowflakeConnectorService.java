package com.example.BACKEND.tenant;

import com.example.BACKEND.catalogue.investigation.WarehouseRowNormalizer;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Snowflake connector service using the official Snowflake JDBC driver.
 *
 * Connection URL format:
 *   jdbc:snowflake://<account>.snowflakecomputing.com/
 *   with Properties: warehouse, db, schema, user, password
 */
@Service
public class SnowflakeConnectorService {

    public record SnowflakeTestResult(
            String connectionLink,
            String account,
            String warehouse,
            String database,
            String schema
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    public SnowflakeTestResult testConnection(
            String account,
            String warehouse,
            String database,
            String schema,
            String username,
            String password
    ) {
        String acc  = require(account,   "account");
        String wh   = require(warehouse, "warehouse");
        String db   = require(database,  "database");
        String sc   = defaultIfBlank(schema, "PUBLIC");
        String user = require(username,  "username");
        String pw   = require(password,  "password");
        validateWarehouseNotAccount(acc, wh);

        String url = buildJdbcUrl(acc, wh, db, sc);
        try (Connection conn = openConnection(url, wh, db, sc, user, pw)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 AS ok");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("Snowflake connection opened but test query returned no rows.");
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Snowflake connection failed: " + ex.getMessage(), ex);
        }

        return new SnowflakeTestResult(buildConnectionLink(acc, wh, db, sc), acc, wh, db, sc);
    }

    public List<String> listTables(
            String account,
            String warehouse,
            String database,
            String schema,
            String username,
            String password
    ) {
        String acc  = require(account,   "account");
        String wh   = require(warehouse, "warehouse");
        String db   = require(database,  "database");
        String sc   = defaultIfBlank(schema, "PUBLIC");
        String user = require(username,  "username");
        String pw   = require(password,  "password");
        validateWarehouseNotAccount(acc, wh);

        String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                     "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' " +
                     "ORDER BY TABLE_NAME";

        String url = buildJdbcUrl(acc, wh, db, sc);
        List<String> tables = new ArrayList<>();

        try (Connection conn = openConnection(url, wh, db, sc, user, pw);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sc.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to list Snowflake tables: " + ex.getMessage(), ex);
        }

        return tables;
    }

    public List<Map<String, Object>> executeSelect(
            String account,
            String warehouse,
            String database,
            String schema,
            String username,
            String password,
            String sql
    ) {
        String acc  = require(account,   "account");
        String wh   = require(warehouse, "warehouse");
        String db   = require(database,  "database");
        String sc   = defaultIfBlank(schema, "PUBLIC");
        String user = require(username,  "username");
        String pw   = require(password,  "password");
        validateWarehouseNotAccount(acc, wh);
        String normalizedSql = require(sql, "sql");

        String url = buildJdbcUrl(acc, wh, db, sc);
        List<Map<String, Object>> rows = new ArrayList<>();

        // DESCRIBE / SHOW are not reliable as PreparedStatements on Snowflake JDBC.
        try (Connection conn = openConnection(url, wh, db, sc, user, pw);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(normalizedSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnLabel(i), normalizeValue(rs.getObject(i)));
                }
                rows.add(row);
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Snowflake query execution failed: " + ex.getMessage(), ex);
        }

        return rows;
    }

    public String buildConnectionLink(String account, String warehouse, String database, String schema) {
        return "snowflake://" + account.trim() +
               "?warehouse=" + warehouse.trim() +
               "&db=" + database.trim() +
               "&schema=" + (schema == null || schema.isBlank() ? "PUBLIC" : schema.trim());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String buildJdbcUrl(String account, String warehouse, String database, String schema) {
        String acc = account.trim();
        // Normalize pasted URLs / JDBC prefixes into a host the driver accepts
        if (acc.startsWith("https://")) acc = acc.substring("https://".length());
        if (acc.startsWith("http://")) acc = acc.substring("http://".length());
        if (acc.startsWith("jdbc:snowflake://")) {
            acc = acc.substring("jdbc:snowflake://".length());
        }
        // Drop path/query if a full host URL was pasted
        int slash = acc.indexOf('/');
        if (slash >= 0) acc = acc.substring(0, slash);
        int q = acc.indexOf('?');
        if (q >= 0) acc = acc.substring(0, q);

        String host = acc.contains(".snowflakecomputing.com")
                ? acc
                : acc + ".snowflakecomputing.com";
        return "jdbc:snowflake://" + host +
                "/?warehouse=" + urlEncode(warehouse) +
                "&db=" + urlEncode(database) +
                "&schema=" + urlEncode(schema) +
                "&JDBC_QUERY_RESULT_FORMAT=JSON";
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private Connection openConnection(
            String url, String warehouse, String database, String schema,
            String username, String password
    ) throws Exception {
        Class.forName("net.snowflake.client.jdbc.SnowflakeDriver");
        System.out.println("[Snowflake] Attempting connection to: " + url);
        System.out.println("[Snowflake] Session context warehouse=" + warehouse
                + " database=" + database + " schema=" + schema);

        Properties props = new Properties();
        props.setProperty("user",      username);
        props.setProperty("password",  password);
        props.setProperty("warehouse", warehouse.toUpperCase(Locale.ROOT));
        props.setProperty("db",        database.toUpperCase(Locale.ROOT));
        props.setProperty("schema",    schema.toUpperCase(Locale.ROOT));
        props.setProperty("loginTimeout",    "20");
        props.setProperty("networkTimeout",  "30");
        props.setProperty("disableTelemetry", "true");
        // Disable Apache Arrow result format — Arrow requires Java module opens
        // that are restricted in Java 17+ / 25. JSON works without JVM --add-opens.
        props.setProperty("JDBC_QUERY_RESULT_FORMAT", "JSON");
        Connection conn = DriverManager.getConnection(url, props);
        // JDBC warehouse/db/schema properties often do not activate the session warehouse.
        // INFORMATION_SCHEMA / DESCRIBE then fail with "No active warehouse selected".
        try (Statement stmt = conn.createStatement()) {
            for (String sql : sessionInitSql(warehouse, database, schema)) {
                try {
                    stmt.execute(sql);
                } catch (Exception ex) {
                    throw new RuntimeException(
                            "Snowflake session init failed (" + sql + "): " + ex.getMessage(), ex);
                }
            }
        }
        return conn;
    }

    /**
     * Session statements that must run after connect and before any discovery SELECT.
     * Package-visible so tests can verify warehouse selection without opening JDBC.
     */
    static List<String> sessionInitSql(String warehouse, String database, String schema) {
        return List.of(
                "ALTER SESSION SET JDBC_QUERY_RESULT_FORMAT = 'JSON'",
                "USE WAREHOUSE " + formatSnowflakeIdent(warehouse),
                "USE DATABASE " + formatSnowflakeIdent(database),
                "USE SCHEMA " + formatSnowflakeIdent(schema)
        );
    }

    /**
     * Format a Snowflake identifier for USE statements.
     * Unquoted-safe names are folded to uppercase (Snowflake unquoted identifier rules).
     * Others are double-quoted with internal quotes escaped.
     */
    static String formatSnowflakeIdent(String raw) {
        String ident = raw == null ? "" : raw.trim();
        if (ident.isEmpty()) {
            throw new IllegalArgumentException("Snowflake identifier is required");
        }
        if (ident.length() > 255) {
            throw new IllegalArgumentException("Snowflake identifier exceeds 255 characters");
        }
        for (int i = 0; i < ident.length(); i++) {
            char c = ident.charAt(i);
            if (c < 32 || c == 127) {
                throw new IllegalArgumentException("Snowflake identifier contains a control character");
            }
        }
        if (ident.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            return ident.toUpperCase(Locale.ROOT);
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** @deprecated use {@link #formatSnowflakeIdent(String)} */
    static String quoteSnowflakeIdent(String raw) {
        return formatSnowflakeIdent(raw);
    }

    private Object normalizeValue(Object value) {
        return WarehouseRowNormalizer.normalizeValue(value);
    }

    static void validateWarehouseNotAccount(String account, String warehouse) {
        if (account != null && warehouse != null
                && account.trim().equalsIgnoreCase(warehouse.trim())) {
            throw new IllegalArgumentException(
                    "warehouse must be a Snowflake warehouse name, not the account identifier");
        }
    }

    private String require(String value, String field) {
        String v = normalize(value);
        if (v == null) throw new IllegalArgumentException(field + " is required");
        return v;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String v = normalize(value);
        return v == null ? defaultValue : v;
    }
}
