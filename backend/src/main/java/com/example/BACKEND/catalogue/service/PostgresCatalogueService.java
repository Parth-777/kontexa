package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.schema.NestedSchemaDiscoverer.DiscoveredField;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.example.BACKEND.tenant.PostgresConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Customer PostgreSQL schema discovery into the common catalogue model.
 * Does not use Kontexa's Spring DataSource or SchemaDiscoveryService.
 */
@Service
public class PostgresCatalogueService {

    private final PostgresConnectorService connectorService;

    public PostgresCatalogueService(PostgresConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    public CatalogueResult discover(TenantCloudConnectionService.PostgresConfig config, boolean withSampling) {
        requireCredentials(config);

        CatalogueResult result = new CatalogueResult();
        result.setDatabaseName(config.database());
        result.setSchemaName(config.schema());

        List<String> tableNames = connectorService.listTables(
                config.host(), config.port(), config.database(), config.schema(),
                config.username(), config.password(), config.ssl());
        System.out.println("[PostgresCatalogue] Discovered " + tableNames.size()
                + " tables in " + config.database() + "." + config.schema());

        for (String tableName : tableNames) {
            TableInfo tableInfo = new TableInfo(tableName, config.schema());
            List<DiscoveredField> discovered = discoverColumns(config, tableName);
            if (discovered.isEmpty()) {
                System.out.println("[PostgresCatalogue] WARNING: no columns found for table " + tableName);
            }
            for (DiscoveredField field : discovered) {
                ColumnInfo col = toColumnInfo(field);
                if (withSampling && !col.isContainer()) {
                    sampleColumn(config, tableName, col);
                }
                tableInfo.addColumn(col);
            }
            tableInfo.setRowCount(fetchRowCount(config, tableName));
            result.addTable(tableInfo);
        }
        return result;
    }

    private List<DiscoveredField> discoverColumns(
            TenantCloudConnectionService.PostgresConfig config,
            String tableName
    ) {
        List<Map<String, Object>> rows;
        try {
            rows = connectorService.listColumns(
                    config.host(), config.port(), config.database(), config.schema(),
                    config.username(), config.password(), config.ssl(), tableName);
        } catch (Exception ex) {
            System.out.println("[PostgresCatalogue] information_schema.columns failed for "
                    + tableName + ": " + ex.getMessage());
            rows = List.of();
        }
        if (rows.isEmpty()) {
            try {
                rows = connectorService.listColumnsFromMetadata(
                        config.host(), config.port(), config.database(), config.schema(),
                        config.username(), config.password(), config.ssl(), tableName);
            } catch (Exception ex) {
                System.out.println("[PostgresCatalogue] JDBC metadata fallback failed for "
                        + tableName + ": " + ex.getMessage());
                return List.of();
            }
        }
        List<DiscoveredField> out = new ArrayList<>();
        for (Map<String, Object> colMeta : rows) {
            String colName = firstNonBlank(colMeta, "COLUMN_NAME", "column_name");
            if (colName == null) {
                continue;
            }
            String dataType = firstNonBlank(colMeta, "DATA_TYPE", "data_type");
            String udtName = firstNonBlank(colMeta, "UDT_NAME", "udt_name");
            boolean nullable = !"NO".equalsIgnoreCase(firstNonBlank(colMeta, "IS_NULLABLE", "is_nullable"));
            String typeLabel = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
            boolean container = isStructuredType(typeLabel, udtName);
            out.add(new DiscoveredField(colName, typeLabel, nullable, "COLUMN", container));
        }
        return out;
    }

    private long fetchRowCount(TenantCloudConnectionService.PostgresConfig config, String tableName) {
        String qualified = PostgresConnectorService.quoteIdent(config.schema())
                + "." + PostgresConnectorService.quoteIdent(tableName);
        String sql = "SELECT COUNT(*) AS CNT FROM " + qualified;
        try {
            List<Map<String, Object>> rows = connectorService.executeSelect(
                    config.host(), config.port(), config.database(), config.schema(),
                    config.username(), config.password(), config.ssl(), sql);
            if (!rows.isEmpty()) {
                Object cnt = firstValue(rows.getFirst(), "CNT", "count");
                if (cnt instanceof Number n) {
                    return n.longValue();
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void sampleColumn(
            TenantCloudConnectionService.PostgresConfig config,
            String tableName,
            ColumnInfo col
    ) {
        String type = col.getDataType() == null ? "" : col.getDataType().toLowerCase(Locale.ROOT);
        String qualifiedCol = PostgresConnectorService.quoteIdent(col.getColumnName());
        String qualifiedTable = PostgresConnectorService.quoteIdent(config.schema())
                + "." + PostgresConnectorService.quoteIdent(tableName);
        try {
            if (isTextType(type)) {
                String sql = "SELECT DISTINCT " + qualifiedCol + " AS V FROM " + qualifiedTable
                        + " WHERE " + qualifiedCol + " IS NOT NULL LIMIT 20";
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.host(), config.port(), config.database(), config.schema(),
                        config.username(), config.password(), config.ssl(), sql);
                List<String> vals = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object v = firstValue(row, "V", "v");
                    if (v != null) {
                        vals.add(String.valueOf(v));
                    }
                }
                col.setSampleValues(vals);
            } else if (isNumericType(type)) {
                String sql = "SELECT MIN(" + qualifiedCol + ") AS MIN_V, MAX(" + qualifiedCol + ") AS MAX_V, "
                        + "AVG(" + qualifiedCol + ") AS AVG_V FROM " + qualifiedTable;
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.host(), config.port(), config.database(), config.schema(),
                        config.username(), config.password(), config.ssl(), sql);
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.getFirst();
                    col.setMinValue(toStr(firstValue(row, "MIN_V", "min_v")));
                    col.setMaxValue(toStr(firstValue(row, "MAX_V", "max_v")));
                    col.setAvgValue(toStr(firstValue(row, "AVG_V", "avg_v")));
                }
            } else if (isDateType(type)) {
                String sql = "SELECT MIN(" + qualifiedCol + ") AS MIN_V, MAX(" + qualifiedCol + ") AS MAX_V FROM "
                        + qualifiedTable;
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.host(), config.port(), config.database(), config.schema(),
                        config.username(), config.password(), config.ssl(), sql);
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.getFirst();
                    col.setMinValue(toStr(firstValue(row, "MIN_V", "min_v")));
                    col.setMaxValue(toStr(firstValue(row, "MAX_V", "max_v")));
                }
            }
        } catch (Exception ex) {
            col.setSkipped(true);
            col.setSkipReason("Sampling error: " + ex.getMessage());
        }
    }

    static boolean isStructuredType(String dataType, String udtName) {
        if (NestedSchemaFieldGraph.isNestedContainerType(dataType)) {
            return true;
        }
        String t = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        String u = udtName == null ? "" : udtName.toLowerCase(Locale.ROOT);
        return t.equals("jsonb")
                || t.equals("json")
                || t.equals("array")
                || u.equals("jsonb")
                || u.equals("json")
                || u.startsWith("_");
    }

    private static ColumnInfo toColumnInfo(DiscoveredField discovered) {
        ColumnInfo col = new ColumnInfo(
                discovered.fieldPath(),
                discovered.dataType(),
                discovered.nullable());
        col.setContainer(discovered.container());
        col.setParentPath(NestedSchemaFieldGraph.parentPath(discovered.fieldPath()));
        col.setFieldMode(discovered.mode());
        if (discovered.container()) {
            col.setRole(NestedSchemaFieldGraph.ROLE_CONTAINER);
        }
        return col;
    }

    private static void requireCredentials(TenantCloudConnectionService.PostgresConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("PostgreSQL connector config is required");
        }
        if (isBlank(config.host()) || isBlank(config.database())
                || isBlank(config.username()) || isBlank(config.password())) {
            throw new IllegalArgumentException(
                    "PostgreSQL connector is incomplete (host/database/username/password). Reconnect PostgreSQL.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isTextType(String dt) {
        return dt.contains("char") || dt.contains("text") || dt.contains("name") || dt.contains("citext");
    }

    private static boolean isNumericType(String dt) {
        return dt.contains("int") || dt.contains("float") || dt.contains("numeric")
                || dt.contains("decimal") || dt.contains("real") || dt.contains("double")
                || dt.equals("money") || dt.equals("smallserial") || dt.equals("serial")
                || dt.equals("bigserial");
    }

    private static boolean isDateType(String dt) {
        return dt.contains("date") || dt.contains("time") || dt.contains("timestamp");
    }

    private static String firstNonBlank(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value == null) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                        value = entry.getValue();
                        break;
                    }
                }
            }
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static Object firstValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key) && row.get(key) != null) {
                return row.get(key);
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
