package com.example.BACKEND.tenant;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

        String url = buildJdbcUrl(acc);
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

        String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                     "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' " +
                     "ORDER BY TABLE_NAME";

        String url = buildJdbcUrl(acc);
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
        String normalizedSql = require(sql, "sql");

        String url = buildJdbcUrl(acc);
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection conn = openConnection(url, wh, db, sc, user, pw);
             PreparedStatement ps = conn.prepareStatement(normalizedSql);
             ResultSet rs = ps.executeQuery()) {
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

    private String buildJdbcUrl(String account) {
        String acc = account.trim();
        // Strip any trailing slash or full URL if accidentally passed
        if (acc.startsWith("jdbc:snowflake://")) return acc;
        if (acc.contains(".snowflakecomputing.com")) {
            return "jdbc:snowflake://" + acc + (acc.endsWith("/") ? "" : "/");
        }
        // Both old format (xy12345.us-east-1) and new org-account format (orgname-accountname)
        // are appended the same way
        return "jdbc:snowflake://" + acc + ".snowflakecomputing.com/";
    }

    private Connection openConnection(
            String url, String warehouse, String database, String schema,
            String username, String password
    ) throws Exception {
        Class.forName("net.snowflake.client.jdbc.SnowflakeDriver");
        System.out.println("[Snowflake] Attempting connection to: " + url);

        Properties props = new Properties();
        props.setProperty("user",      username);
        props.setProperty("password",  password);
        props.setProperty("warehouse", warehouse.toUpperCase());
        props.setProperty("db",        database.toUpperCase());
        props.setProperty("schema",    schema.toUpperCase());
        props.setProperty("loginTimeout",    "20");
        props.setProperty("networkTimeout",  "30");
        props.setProperty("disableTelemetry", "true");
        // Disable Apache Arrow result format — Arrow requires Java module opens
        // that are restricted in Java 17+. JSON format works on all JVM versions.
        props.setProperty("JDBC_QUERY_RESULT_FORMAT", "JSON");
        return DriverManager.getConnection(url, props);
    }

    private Object normalizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date d)      return d.toLocalDate().toString();
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (value instanceof java.sql.Time t)       return t.toLocalTime().toString();
        return value;
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
