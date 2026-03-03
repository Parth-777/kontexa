package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "client_catalogues")
public class ClientCatalogueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "database_name")
    private String databaseName;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    /** DRAFT → APPROVED → (REJECTED) */
    @Column(name = "status", nullable = false)
    private String status = "DRAFT";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "catalogue", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CatalogueTableEntity> tables = new ArrayList<>();

    // ── Getters / Setters ────────────────────────────────────────────

    public Long getId()                              { return id; }
    public String getClientId()                      { return clientId; }
    public void   setClientId(String clientId)       { this.clientId = clientId; }
    public String getDatabaseName()                  { return databaseName; }
    public void   setDatabaseName(String db)         { this.databaseName = db; }
    public String getSchemaName()                    { return schemaName; }
    public void   setSchemaName(String s)            { this.schemaName = s; }
    public String getStatus()                        { return status; }
    public void   setStatus(String status)           { this.status = status; }
    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void          setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void          setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
    public List<CatalogueTableEntity> getTables()    { return tables; }
    public void setTables(List<CatalogueTableEntity> t) { this.tables = t; }
}
