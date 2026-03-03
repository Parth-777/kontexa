package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "catalogue_tables")
public class CatalogueTableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "catalogue_id", nullable = false)
    private ClientCatalogueEntity catalogue;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "table_schema", nullable = false)
    private String tableSchema;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "row_count")
    private Long rowCount;

    @OneToMany(mappedBy = "catalogueTable", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CatalogueColumnEntity> columns = new ArrayList<>();

    // ── Getters / Setters ────────────────────────────────────────────

    public Long getId()                                   { return id; }
    public ClientCatalogueEntity getCatalogue()           { return catalogue; }
    public void setCatalogue(ClientCatalogueEntity c)     { this.catalogue = c; }
    public String getTableName()                          { return tableName; }
    public void   setTableName(String s)                  { this.tableName = s; }
    public String getTableSchema()                        { return tableSchema; }
    public void   setTableSchema(String s)                { this.tableSchema = s; }
    public String getDescription()                        { return description; }
    public void   setDescription(String s)                { this.description = s; }
    public Long   getRowCount()                           { return rowCount; }
    public void   setRowCount(Long r)                     { this.rowCount = r; }
    public List<CatalogueColumnEntity> getColumns()       { return columns; }
    public void setColumns(List<CatalogueColumnEntity> c) { this.columns = c; }
}
