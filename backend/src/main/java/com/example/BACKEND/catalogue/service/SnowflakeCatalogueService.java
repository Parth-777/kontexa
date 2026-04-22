package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SnowflakeCatalogueService {

    private final SnowflakeConnectorService connectorService;

    public SnowflakeCatalogueService(SnowflakeConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    public CatalogueResult discover(TenantCloudConnectionService.SnowflakeConfig config, boolean withSampling) {
        CatalogueResult result = new CatalogueResult();
        result.setDatabaseName(config.database());
        result.setSchemaName(config.schema());

        List<String> tableNames = connectorService.listTables(
                config.account(), config.warehouse(), config.database(),
                config.schema(), config.username(), config.password()
        );

        for (String tableName : tableNames) {
            TableInfo tableInfo = new TableInfo(tableName, config.schema());

            List<Map<String, Object>> columns = fetchColumnMetadata(config, tableName);
            for (Map<String, Object> colMeta : columns) {
                String colName  = toStr(colMeta.get("COLUMN_NAME"));
                String dataType = toStr(colMeta.get("DATA_TYPE"));
                boolean nullable = !"NO".equalsIgnoreCase(toStr(colMeta.get("IS_NULLABLE")));
                ColumnInfo col = new ColumnInfo(colName, dataType == null ? "" : dataType.toLowerCase(), nullable);
                if (withSampling) {
                    sampleColumn(config, tableName, col);
                }
                tableInfo.addColumn(col);
            }

            long rowCount = fetchRowCount(config, tableName);
            tableInfo.setRowCount(rowCount);
            result.addTable(tableInfo);
        }

        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Map<String, Object>> fetchColumnMetadata(
            TenantCloudConnectionService.SnowflakeConfig config, String tableName
    ) {
        // Snowflake INFORMATION_SCHEMA uses uppercase identifiers by default
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE " +
                     "FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = '" + escapeSql(config.schema().toUpperCase()) + "' " +
                     "AND TABLE_NAME = '" + escapeSql(tableName.toUpperCase()) + "' " +
                     "ORDER BY ORDINAL_POSITION";
        try {
            return connectorService.executeSelect(
                    config.account(), config.warehouse(), config.database(),
                    config.schema(), config.username(), config.password(), sql
            );
        } catch (Exception ex) {
            return List.of();
        }
    }

    private long fetchRowCount(TenantCloudConnectionService.SnowflakeConfig config, String tableName) {
        String qualifiedTable = config.schema().toUpperCase() + ".\"" + tableName + "\"";
        String sql = "SELECT COUNT(*) AS CNT FROM " + qualifiedTable;
        try {
            List<Map<String, Object>> rows = connectorService.executeSelect(
                    config.account(), config.warehouse(), config.database(),
                    config.schema(), config.username(), config.password(), sql
            );
            if (!rows.isEmpty()) {
                Object cnt = rows.getFirst().get("CNT");
                if (cnt instanceof Number n) return n.longValue();
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void sampleColumn(
            TenantCloudConnectionService.SnowflakeConfig config, String tableName, ColumnInfo col
    ) {
        String type = col.getDataType().toLowerCase();
        String qualifiedCol   = "\"" + col.getColumnName() + "\"";
        String qualifiedTable = config.schema().toUpperCase() + ".\"" + tableName + "\"";

        try {
            if (isTextType(type)) {
                String sql = "SELECT DISTINCT " + qualifiedCol + " AS V FROM " + qualifiedTable
                        + " WHERE " + qualifiedCol + " IS NOT NULL LIMIT 20";
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.account(), config.warehouse(), config.database(),
                        config.schema(), config.username(), config.password(), sql
                );
                List<String> vals = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object v = row.get("V");
                    if (v != null) vals.add(String.valueOf(v));
                }
                col.setSampleValues(vals);

            } else if (isNumericType(type)) {
                String sql = "SELECT MIN(" + qualifiedCol + ") AS MIN_V, MAX(" + qualifiedCol + ") AS MAX_V, "
                        + "AVG(" + qualifiedCol + ") AS AVG_V FROM " + qualifiedTable;
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.account(), config.warehouse(), config.database(),
                        config.schema(), config.username(), config.password(), sql
                );
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.getFirst();
                    col.setMinValue(toStr(row.get("MIN_V")));
                    col.setMaxValue(toStr(row.get("MAX_V")));
                    col.setAvgValue(toStr(row.get("AVG_V")));
                }

            } else if (isDateType(type)) {
                String sql = "SELECT MIN(" + qualifiedCol + ") AS MIN_V, MAX(" + qualifiedCol + ") AS MAX_V "
                        + "FROM " + qualifiedTable;
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.account(), config.warehouse(), config.database(),
                        config.schema(), config.username(), config.password(), sql
                );
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.getFirst();
                    col.setMinValue(toStr(row.get("MIN_V")));
                    col.setMaxValue(toStr(row.get("MAX_V")));
                }
            }
        } catch (Exception ex) {
            col.setSkipped(true);
            col.setSkipReason("Sampling error: " + ex.getMessage());
        }
    }

    private boolean isTextType(String dt) {
        return dt.contains("char") || dt.contains("text") || dt.contains("string")
                || dt.contains("varchar") || dt.contains("nchar");
    }

    private boolean isNumericType(String dt) {
        return dt.contains("int") || dt.contains("float") || dt.contains("number")
                || dt.contains("numeric") || dt.contains("decimal") || dt.contains("real")
                || dt.contains("double") || dt.contains("fixed");
    }

    private boolean isDateType(String dt) {
        return dt.contains("date") || dt.contains("time") || dt.contains("timestamp");
    }

    private String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String escapeSql(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
