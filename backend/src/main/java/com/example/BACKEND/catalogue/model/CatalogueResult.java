package com.example.BACKEND.catalogue.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The top-level result object returned after running the full catalogue
 * pipeline on a client's database.
 *
 * Contains:
 *  - All discovered tables and their columns (from SchemaDiscoveryService)
 *  - Sampled values per column (from DataSamplerService)
 *  - Metadata about the catalogue run itself
 *
 * This is the object that gets sent to the client for review and approval.
 */
public class CatalogueResult {

    private String databaseName;
    private String schemaName;
    private LocalDateTime discoveredAt;
    private List<TableInfo> tables = new ArrayList<>();
    private String status; // DRAFT, APPROVED, REJECTED

    // Summary stats
    private int totalTables;
    private int totalColumns;

    public CatalogueResult() {
        this.discoveredAt = LocalDateTime.now();
        this.status = "DRAFT";
    }

    public CatalogueResult(String databaseName, String schemaName) {
        this();
        this.databaseName = databaseName;
        this.schemaName = schemaName;
    }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }

    public LocalDateTime getDiscoveredAt() { return discoveredAt; }
    public void setDiscoveredAt(LocalDateTime discoveredAt) { this.discoveredAt = discoveredAt; }

    public List<TableInfo> getTables() { return tables; }
    public void setTables(List<TableInfo> tables) {
        this.tables = tables;
        this.totalTables = tables.size();
        this.totalColumns = tables.stream()
                .mapToInt(t -> t.getColumns().size())
                .sum();
    }

    public void addTable(TableInfo table) {
        this.tables.add(table);
        this.totalTables = this.tables.size();
        this.totalColumns = this.tables.stream()
                .mapToInt(t -> t.getColumns().size())
                .sum();
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getTotalTables() { return totalTables; }
    public int getTotalColumns() { return totalColumns; }
}
