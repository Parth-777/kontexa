package com.example.BACKEND.catalogue.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single table discovered from a client's database.
 * Contains all columns discovered for that table.
 */
public class TableInfo {

    private String tableName;
    private String tableSchema;
    private long rowCount;
    private List<ColumnInfo> columns = new ArrayList<>();

    public TableInfo() {}

    public TableInfo(String tableName, String tableSchema) {
        this.tableName = tableName;
        this.tableSchema = tableSchema;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getTableSchema() { return tableSchema; }
    public void setTableSchema(String tableSchema) { this.tableSchema = tableSchema; }

    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }

    public List<ColumnInfo> getColumns() { return columns; }
    public void setColumns(List<ColumnInfo> columns) { this.columns = columns; }

    public void addColumn(ColumnInfo column) {
        this.columns.add(column);
    }

    /** Convenience: get a column by name */
    public ColumnInfo getColumn(String columnName) {
        return columns.stream()
                .filter(c -> c.getColumnName().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
    }
}
