package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SchemaDiscoveryService
 *
 * Connects to a client's database and reads its full structure from
 * information_schema — tables, columns, data types, nullable flags.
 *
 * This works for any relational database (PostgreSQL, MySQL, etc.)
 * because information_schema is a SQL standard.
 *
 * Nothing is hardcoded — it discovers whatever the client has.
 */
@Service
public class SchemaDiscoveryService {

    // Tables owned by PostgreSQL internals — we never catalogue these
    private static final List<String> SYSTEM_SCHEMAS = List.of(
            "information_schema", "pg_catalog", "pg_toast"
    );

    // Column types that hold meaningful categorical values worth sampling
    private static final List<String> TEXT_TYPES = List.of(
            "text", "varchar", "character varying", "char", "character",
            "bpchar", "name", "citext"
    );

    // Column types that hold numeric values worth ranging
    private static final List<String> NUMERIC_TYPES = List.of(
            "integer", "int", "int4", "int8", "bigint", "smallint",
            "numeric", "decimal", "real", "double precision", "float4", "float8"
    );

    // Column types that hold timestamps worth ranging
    private static final List<String> TIMESTAMP_TYPES = List.of(
            "timestamp", "timestamptz", "timestamp without time zone",
            "timestamp with time zone", "date"
    );

    private final DataSource dataSource;

    public SchemaDiscoveryService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Main entry point.
     *
     * Discovers all tables and their columns in the given schema
     * using the application's current database connection.
     *
     * @param schemaName e.g. "public"
     * @return CatalogueResult populated with tables and columns (no sampling yet)
     */
    public CatalogueResult discover(String schemaName) {
        CatalogueResult result = new CatalogueResult();
        result.setSchemaName(schemaName);

        try (Connection conn = dataSource.getConnection()) {

            result.setDatabaseName(conn.getCatalog());

            List<String> tableNames = discoverTables(conn, schemaName);
            System.out.println("[SchemaDiscovery] Found " + tableNames.size()
                    + " tables in schema '" + schemaName + "'");

            for (String tableName : tableNames) {
                TableInfo tableInfo = new TableInfo(tableName, schemaName);

                // Count rows in this table
                long rowCount = countRows(conn, schemaName, tableName);
                tableInfo.setRowCount(rowCount);

                // Discover columns
                List<ColumnInfo> columns = discoverColumns(conn, schemaName, tableName);
                columns.forEach(tableInfo::addColumn);

                result.addTable(tableInfo);

                System.out.println("[SchemaDiscovery] Table: " + tableName
                        + " | Rows: " + rowCount
                        + " | Columns: " + columns.size());
            }

        } catch (SQLException e) {
            throw new RuntimeException("Schema discovery failed: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * Reads all user-owned table names from information_schema.tables
     * for the given schema.
     */
    private List<String> discoverTables(Connection conn, String schemaName) throws SQLException {
        List<String> tables = new ArrayList<>();

        String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
        }

        return tables;
    }

    /**
     * Reads all columns for a given table from information_schema.columns.
     * Returns column name, data type, and nullable flag.
     */
    private List<ColumnInfo> discoverColumns(Connection conn,
                                             String schemaName,
                                             String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();

        String sql = """
                SELECT column_name,
                       data_type,
                       is_nullable
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name   = ?
                ORDER BY ordinal_position
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName   = rs.getString("column_name");
                    String dataType  = rs.getString("data_type");
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));

                    columns.add(new ColumnInfo(colName, dataType, nullable));
                }
            }
        }

        return columns;
    }

    /**
     * Counts rows in the given table.
     * Returns -1 if count fails (permission issue, view, etc.)
     */
    private long countRows(Connection conn, String schemaName, String tableName) {
        String sql = "SELECT COUNT(*) FROM " + schemaName + "." + tableName;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            System.out.println("[SchemaDiscovery] Could not count rows for "
                    + tableName + ": " + e.getMessage());
        }
        return -1;
    }

    // --- Type classification helpers (used by DataSamplerService) ---

    public boolean isTextType(String dataType) {
        return TEXT_TYPES.stream().anyMatch(t -> t.equalsIgnoreCase(dataType));
    }

    public boolean isNumericType(String dataType) {
        return NUMERIC_TYPES.stream().anyMatch(t -> t.equalsIgnoreCase(dataType));
    }

    public boolean isTimestampType(String dataType) {
        return TIMESTAMP_TYPES.stream().anyMatch(t -> t.equalsIgnoreCase(dataType));
    }
}
