package com.example.BACKEND.catalogue.service;

import com.example.BACKEND.catalogue.model.CatalogueResult;
import com.example.BACKEND.catalogue.model.ColumnInfo;
import com.example.BACKEND.catalogue.model.TableInfo;
import com.example.BACKEND.catalogue.schema.NestedSchemaDiscoverer;
import com.example.BACKEND.catalogue.schema.NestedSchemaDiscoverer.DiscoveredField;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.example.BACKEND.catalogue.schema.SqlFieldReferenceEmitter;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BigQueryCatalogueService {

    private final BigQueryConnectorService connectorService;

    public BigQueryCatalogueService(BigQueryConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    public CatalogueResult discover(TenantCloudConnectionService.BigQueryConfig config, boolean withSampling) {
        CatalogueResult result = new CatalogueResult();
        result.setDatabaseName(config.projectId());
        result.setSchemaName(config.dataset());

        List<String> tables = connectorService.listTables(
                config.projectId(),
                config.serviceAccountJson(),
                config.location(),
                config.dataset()
        );

        for (String tableName : tables) {
            TableInfo tableInfo = new TableInfo(tableName, config.dataset());
            Table table = connectorService.getTable(
                    config.projectId(),
                    config.serviceAccountJson(),
                    config.location(),
                    config.dataset(),
                    tableName
            );
            if (table != null && table.getDefinition() instanceof StandardTableDefinition stdDef) {
                Long rows = stdDef.getNumRows();
                tableInfo.setRowCount(rows == null ? -1 : rows);
                Schema schema = stdDef.getSchema();
                if (schema != null && schema.getFields() != null) {
                    NestedSchemaDiscoverer.discoverBigQueryFields(schema.getFields(), discovered -> {
                        ColumnInfo col = toColumnInfo(discovered);
                        if (withSampling && !col.isContainer()) {
                            sampleColumn(config, tableName, col);
                        }
                        tableInfo.addColumn(col);
                    });
                }
            }
            result.addTable(tableInfo);
        }
        return result;
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

    private void sampleColumn(TenantCloudConnectionService.BigQueryConfig config, String tableName, ColumnInfo col) {
        String type = col.getDataType().toLowerCase();
        String columnRef = SqlFieldReferenceEmitter.emitQuotedReference(col.getColumnName());
        String tableRef = "`" + config.projectId() + "." + config.dataset() + "." + tableName + "`";
        try {
            if (isTextType(type)) {
                String sql = "SELECT DISTINCT " + columnRef + " AS v FROM " + tableRef
                        + " WHERE " + columnRef + " IS NOT NULL LIMIT 20";
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.projectId(), config.serviceAccountJson(), config.location(), config.dataset(), sql
                );
                List<String> vals = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object v = row.get("v");
                    if (v != null) vals.add(String.valueOf(v));
                }
                col.setSampleValues(vals);
            } else if (isNumericType(type)) {
                String sql = "SELECT MIN(" + columnRef + ") AS min_v, MAX(" + columnRef + ") AS max_v, "
                        + "AVG(" + columnRef + ") AS avg_v FROM " + tableRef;
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.projectId(), config.serviceAccountJson(), config.location(), config.dataset(), sql
                );
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.getFirst();
                    col.setMinValue(toStr(row.get("min_v")));
                    col.setMaxValue(toStr(row.get("max_v")));
                    col.setAvgValue(toStr(row.get("avg_v")));
                }
            } else if (isDateType(type)) {
                String sql = "SELECT MIN(" + columnRef + ") AS min_v, MAX(" + columnRef + ") AS max_v FROM " + tableRef;
                List<Map<String, Object>> rows = connectorService.executeSelect(
                        config.projectId(), config.serviceAccountJson(), config.location(), config.dataset(), sql
                );
                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.getFirst();
                    col.setMinValue(toStr(row.get("min_v")));
                    col.setMaxValue(toStr(row.get("max_v")));
                }
            }
        } catch (Exception ex) {
            col.setSkipped(true);
            col.setSkipReason("Sampling error: " + ex.getMessage());
        }
    }

    private String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private boolean isTextType(String dt) {
        return dt.contains("string");
    }

    private boolean isNumericType(String dt) {
        return dt.contains("int") || dt.contains("float") || dt.contains("numeric")
                || dt.contains("bignumeric") || dt.contains("decimal");
    }

    private boolean isDateType(String dt) {
        return dt.contains("date") || dt.contains("time") || dt.contains("timestamp");
    }
}
