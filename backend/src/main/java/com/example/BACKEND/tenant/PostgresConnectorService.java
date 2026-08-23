package com.example.BACKEND.tenant;

import com.example.BACKEND.catalogue.investigation.WarehouseRowNormalizer;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
 * Customer PostgreSQL warehouse connector (JDBC).
 * Does not use Kontexa's Spring DataSource.
 */
@Service
public class PostgresConnectorService {

    public static final int DEFAULT_PORT = 5432;
    public static final String DEFAULT_SCHEMA = "public";

    public record PostgresTestResult(
            String connectionLink,
            String host,
            int port,
            String database,
            String schema,
            boolean ssl
    ) {}

    public PostgresTestResult testConnection(
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            String password,
            boolean ssl
    ) {
        ConnectionSpec spec = requireSpec(host, port, database, schema, username, password, ssl);
        String url = buildJdbcUrl(spec.host(), spec.port(), spec.database(), spec.ssl());
        try (Connection conn = openConnection(url, spec.username(), spec.password())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 AS ok");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException("PostgreSQL connection opened but test query returned no rows.");
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("PostgreSQL connection failed: " + ex.getMessage(), ex);
        }
        return new PostgresTestResult(
                buildConnectionLink(spec.host(), spec.port(), spec.database(), spec.schema(), spec.ssl()),
                spec.host(), spec.port(), spec.database(), spec.schema(), spec.ssl());
    }

    public List<String> listTables(
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            String password,
            boolean ssl
    ) {
        ConnectionSpec spec = requireSpec(host, port, database, schema, username, password, ssl);
        String sql = "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                + "ORDER BY table_name";
        String url = buildJdbcUrl(spec.host(), spec.port(), spec.database(), spec.ssl());
        List<String> tables = new ArrayList<>();
        try (Connection conn = openConnection(url, spec.username(), spec.password());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, spec.schema());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isBlank()) {
                        tables.add(name);
                    }
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to list PostgreSQL tables: " + ex.getMessage(), ex);
        }
        return tables;
    }

    public List<Map<String, Object>> listColumns(
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            String password,
            boolean ssl,
            String tableName
    ) {
        ConnectionSpec spec = requireSpec(host, port, database, schema, username, password, ssl);
        String table = require(tableName, "tableName");
        String sql = "SELECT column_name, data_type, udt_name, is_nullable, ordinal_position "
                + "FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? "
                + "ORDER BY ordinal_position";
        String url = buildJdbcUrl(spec.host(), spec.port(), spec.database(), spec.ssl());
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = openConnection(url, spec.username(), spec.password());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, spec.schema());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(readNormalizedRow(rs));
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to list PostgreSQL columns: " + ex.getMessage(), ex);
        }
        return rows;
    }

    /**
     * JDBC DatabaseMetaData fallback when information_schema.columns returns nothing.
     */
    public List<Map<String, Object>> listColumnsFromMetadata(
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            String password,
            boolean ssl,
            String tableName
    ) {
        ConnectionSpec spec = requireSpec(host, port, database, schema, username, password, ssl);
        String table = require(tableName, "tableName");
        String url = buildJdbcUrl(spec.host(), spec.port(), spec.database(), spec.ssl());
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = openConnection(url, spec.username(), spec.password())) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(spec.database(), spec.schema(), table, null)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("COLUMN_NAME", rs.getString("COLUMN_NAME"));
                    row.put("DATA_TYPE", rs.getString("TYPE_NAME"));
                    row.put("UDT_NAME", rs.getString("TYPE_NAME"));
                    int nullable = rs.getInt("NULLABLE");
                    row.put("IS_NULLABLE", nullable == DatabaseMetaData.columnNoNulls ? "NO" : "YES");
                    row.put("ORDINAL_POSITION", rs.getInt("ORDINAL_POSITION"));
                    rows.add(row);
                }
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to read PostgreSQL JDBC metadata: " + ex.getMessage(), ex);
        }
        return rows;
    }

    public List<Map<String, Object>> executeSelect(
            String host,
            Integer port,
            String database,
            String schema,
            String username,
            String password,
            boolean ssl,
            String sql
    ) {
        ConnectionSpec spec = requireSpec(host, port, database, schema, username, password, ssl);
        String normalizedSql = require(sql, "sql");
        if (!normalizedSql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT")) {
            throw new IllegalArgumentException("Only SELECT statements are allowed");
        }
        String url = buildJdbcUrl(spec.host(), spec.port(), spec.database(), spec.ssl());
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = openConnection(url, spec.username(), spec.password());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(normalizedSql)) {
            while (rs.next()) {
                rows.add(readNormalizedRow(rs));
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("PostgreSQL query execution failed: " + ex.getMessage(), ex);
        }
        return rows;
    }

    public String buildConnectionLink(String host, int port, String database, String schema, boolean ssl) {
        return "postgresql://" + host + ":" + port + "/" + database
                + "?schema=" + schema
                + "&ssl=" + ssl;
    }

    static String buildJdbcUrl(String host, int port, String database, boolean ssl) {
        String sslMode = ssl ? "require" : "disable";
        return "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?sslmode=" + sslMode;
    }

    public static String quoteIdent(String ident) {
        if (ident == null || ident.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL identifier is required");
        }
        String trimmed = ident.trim();
        if (trimmed.length() > 63) {
            throw new IllegalArgumentException("PostgreSQL identifier exceeds 63 characters");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 32 || c == 127) {
                throw new IllegalArgumentException("PostgreSQL identifier contains a control character");
            }
        }
        return "\"" + trimmed.replace("\"", "\"\"") + "\"";
    }

    static int normalizePort(Integer port) {
        if (port == null) {
            return DEFAULT_PORT;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return port;
    }

    private Connection openConnection(String url, String username, String password) throws Exception {
        Class.forName("org.postgresql.Driver");
        System.out.println("[PostgreSQL] Attempting connection to: " + url);
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("loginTimeout", "20");
        props.setProperty("connectTimeout", "20");
        return DriverManager.getConnection(url, props);
    }

    private Map<String, Object> readNormalizedRow(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= colCount; i++) {
            String label = meta.getColumnLabel(i);
            if (label != null) {
                label = label.toUpperCase(Locale.ROOT);
            }
            row.put(label, WarehouseRowNormalizer.normalizeValue(rs.getObject(i)));
        }
        return row;
    }

    private ConnectionSpec requireSpec(
            String host, Integer port, String database, String schema,
            String username, String password, boolean ssl
    ) {
        String h = require(host, "host");
        if (h.contains("/") || h.contains("\\") || h.contains(" ")) {
            throw new IllegalArgumentException("host must be a hostname or IP address");
        }
        int p = normalizePort(port);
        String db = require(database, "database");
        String sc = defaultIfBlank(schema, DEFAULT_SCHEMA);
        String user = require(username, "username");
        String pw = require(password, "password");
        return new ConnectionSpec(h, p, db, sc, user, pw, ssl);
    }

    private String require(String value, String field) {
        String v = normalize(value);
        if (v == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return v;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        String v = normalize(value);
        return v == null ? defaultValue : v;
    }

    private record ConnectionSpec(
            String host,
            int port,
            String database,
            String schema,
            String username,
            String password,
            boolean ssl
    ) {}
}
