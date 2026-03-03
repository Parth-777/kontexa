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
 * DataSamplerService
 *
 * Takes a CatalogueResult that has been populated by SchemaDiscoveryService
 * (tables + columns structure) and enriches each column with real data
 * sampled from the client's database.
 *
 * Sampling strategy per column type:
 *
 *   TEXT columns
 *     → Count distinct values first
 *     → If distinct count <= MAX_DISTINCT_SAMPLE: collect all distinct values
 *     → If distinct count >  MAX_DISTINCT_SAMPLE: mark as skipped (e.g. emails, IDs)
 *
 *   NUMERIC columns
 *     → Get MIN, MAX, AVG
 *
 *   TIMESTAMP columns
 *     → Get MIN and MAX (tells us the data date range)
 *
 *   UUID / BINARY / other
 *     → Skip (no useful information for NLP)
 *
 * After sampling, each ColumnInfo has real values that the LLM
 * can use to map English words to actual database values.
 */
@Service
public class DataSamplerService {

    // If a text column has more than this many distinct values,
    // it's probably free-text or an ID — skip sampling
    private static final int MAX_DISTINCT_SAMPLE = 2000;

    // How many distinct values to collect per column (sent to LLM)
    private static final int SAMPLE_LIMIT = 30;

    // Column name patterns that are almost certainly IDs — skip them
    private static final List<String> ID_COLUMN_PATTERNS = List.of(
            "_id", "uuid", "guid", "hash", "token", "secret",
            "password", "email", "phone", "ip_address", "ip"
    );

    private final DataSource dataSource;
    private final SchemaDiscoveryService schemaDiscovery;

    public DataSamplerService(DataSource dataSource,
                              SchemaDiscoveryService schemaDiscovery) {
        this.dataSource = dataSource;
        this.schemaDiscovery = schemaDiscovery;
    }

    /**
     * Main entry point.
     *
     * Takes a CatalogueResult (already populated with tables + columns)
     * and fills in sample values for every column.
     * Modifies the CatalogueResult in place.
     *
     * @param catalogue from SchemaDiscoveryService
     * @return same catalogue, now enriched with sample values
     */
    public CatalogueResult sample(CatalogueResult catalogue) {
        try (Connection conn = dataSource.getConnection()) {

            for (TableInfo table : catalogue.getTables()) {
                System.out.println("[DataSampler] Sampling table: " + table.getTableName());

                for (ColumnInfo column : table.getColumns()) {
                    sampleColumn(conn, table, column);
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("Data sampling failed: " + e.getMessage(), e);
        }

        return catalogue;
    }

    /**
     * Decides how to sample a single column based on its data type and name,
     * then runs the appropriate query.
     */
    private void sampleColumn(Connection conn, TableInfo table, ColumnInfo column) {
        String colName  = column.getColumnName();
        String dataType = column.getDataType();
        String fullRef  = table.getTableSchema() + "." + table.getTableName() + "." + colName;

        // Skip columns that look like IDs, passwords, tokens etc.
        if (looksLikeId(colName)) {
            column.setSkipped(true);
            column.setSkipReason("Column name suggests identifier or sensitive data");
            System.out.println("[DataSampler] Skipped (ID pattern): " + fullRef);
            return;
        }

        try {
            if (schemaDiscovery.isTextType(dataType)) {
                sampleTextColumn(conn, table, column);

            } else if (schemaDiscovery.isNumericType(dataType)) {
                sampleNumericColumn(conn, table, column);

            } else if (schemaDiscovery.isTimestampType(dataType)) {
                sampleTimestampColumn(conn, table, column);

            } else {
                // uuid, jsonb, bytea, array, etc. — skip
                column.setSkipped(true);
                column.setSkipReason("Data type '" + dataType + "' is not sampled");
                System.out.println("[DataSampler] Skipped (unsupported type): " + fullRef);
            }

        } catch (SQLException e) {
            column.setSkipped(true);
            column.setSkipReason("Sampling error: " + e.getMessage());
            System.out.println("[DataSampler] Error sampling " + fullRef + ": " + e.getMessage());
        }
    }

    /**
     * For text columns:
     * 1. Count distinct values
     * 2. If <= MAX_DISTINCT_SAMPLE → collect them all
     * 3. If >  MAX_DISTINCT_SAMPLE → skip (free text / high cardinality)
     */
    private void sampleTextColumn(Connection conn,
                                   TableInfo table,
                                   ColumnInfo column) throws SQLException {
        String qualifiedTable = table.getTableSchema() + ".\"" + table.getTableName() + "\"";
        String quotedCol      = "\"" + column.getColumnName() + "\"";

        // Step 1: Count distinct values
        String countSql = "SELECT COUNT(DISTINCT " + quotedCol + ") FROM " + qualifiedTable;
        long distinctCount = 0;

        try (PreparedStatement ps = conn.prepareStatement(countSql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) distinctCount = rs.getLong(1);
        }

        if (distinctCount > MAX_DISTINCT_SAMPLE) {
            column.setSkipped(true);
            column.setSkipReason("High cardinality: " + distinctCount + " distinct values (limit is " + MAX_DISTINCT_SAMPLE + ")");
            System.out.println("[DataSampler] Skipped (high cardinality " + distinctCount + "): "
                    + table.getTableName() + "." + column.getColumnName());
            return;
        }

        // Step 2: Collect all distinct values (up to SAMPLE_LIMIT)
        String sampleSql = "SELECT DISTINCT " + quotedCol
                + " FROM " + qualifiedTable
                + " WHERE " + quotedCol + " IS NOT NULL"
                + " ORDER BY " + quotedCol
                + " LIMIT " + SAMPLE_LIMIT;

        List<String> values = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sampleSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String val = rs.getString(1);
                if (val != null && !val.isBlank()) {
                    values.add(val);
                }
            }
        }

        column.setSampleValues(values);
        System.out.println("[DataSampler] Sampled " + values.size() + " values for "
                + table.getTableName() + "." + column.getColumnName()
                + ": " + values);
    }

    /**
     * For numeric columns: get MIN, MAX, and AVG.
     * These help the LLM understand "expensive products" (high order_total)
     * or "small orders" (low quantity).
     */
    private void sampleNumericColumn(Connection conn,
                                      TableInfo table,
                                      ColumnInfo column) throws SQLException {
        String qualifiedTable = table.getTableSchema() + ".\"" + table.getTableName() + "\"";
        String quotedCol      = "\"" + column.getColumnName() + "\"";

        String sql = "SELECT MIN(" + quotedCol + "), MAX(" + quotedCol + "), AVG(" + quotedCol + ")"
                + " FROM " + qualifiedTable;

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String min = rs.getString(1);
                String max = rs.getString(2);
                String avg = rs.getString(3);

                column.setMinValue(min);
                column.setMaxValue(max);
                // Round avg to 2 decimal places for readability
                if (avg != null) {
                    try {
                        column.setAvgValue(String.format("%.2f", Double.parseDouble(avg)));
                    } catch (NumberFormatException e) {
                        column.setAvgValue(avg);
                    }
                }

                System.out.println("[DataSampler] Numeric range for "
                        + table.getTableName() + "." + column.getColumnName()
                        + ": min=" + min + " max=" + max + " avg=" + column.getAvgValue());
            }
        }
    }

    /**
     * For timestamp columns: get MIN and MAX.
     * This tells us the time range of the data — useful for the LLM to
     * understand "recent data" vs "historical data".
     */
    private void sampleTimestampColumn(Connection conn,
                                        TableInfo table,
                                        ColumnInfo column) throws SQLException {
        String qualifiedTable = table.getTableSchema() + ".\"" + table.getTableName() + "\"";
        String quotedCol      = "\"" + column.getColumnName() + "\"";

        String sql = "SELECT MIN(" + quotedCol + "), MAX(" + quotedCol + ")"
                + " FROM " + qualifiedTable;

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                column.setMinValue(rs.getString(1));
                column.setMaxValue(rs.getString(2));

                System.out.println("[DataSampler] Timestamp range for "
                        + table.getTableName() + "." + column.getColumnName()
                        + ": " + column.getMinValue() + " → " + column.getMaxValue());
            }
        }
    }

    /**
     * Returns true if the column name looks like an identifier or sensitive field
     * that should not be sampled.
     */
    private boolean looksLikeId(String columnName) {
        String lower = columnName.toLowerCase();
        return ID_COLUMN_PATTERNS.stream().anyMatch(lower::contains);
    }
}
