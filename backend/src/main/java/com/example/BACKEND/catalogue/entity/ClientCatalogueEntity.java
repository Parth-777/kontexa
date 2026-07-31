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

    /** Canonical tenant ownership (workspaces.id). Dual-written with client_id during Phase B. */
    @Column(name = "tenant_id")
    private java.util.UUID tenantId;

    @Column(name = "database_name")
    private String databaseName;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    /** DRAFT → APPROVED → (REJECTED) */
    @Column(name = "status", nullable = false)
    private String status = "DRAFT";

    /**
     * Stable lineage key across rediscoveries / future catalogue versions.
     * Same logical dataset keeps the same lineage_key; each row is one version.
     * Null until evolution logic assigns one (safe additive column).
     */
    @Column(name = "lineage_key")
    private String lineageKey;

    /**
     * Prior catalogue row this version supersedes (same lineage). Null for first version.
     * Enables future carry-forward without restructuring tables.
     */
    @Column(name = "supersedes_catalogue_id")
    private Long supersedesCatalogueId;

    /** Dataset-level business profile prose (semantic curriculum). */
    @Column(name = "business_profile", columnDefinition = "TEXT")
    private String businessProfile;

    /** JSON array string of cross-field caveats. */
    @Column(name = "global_caveats", columnDefinition = "TEXT")
    private String globalCaveats;

    /** JSON array of light entities: [{name, description, grain}, ...]. */
    @Column(name = "entities_json", columnDefinition = "TEXT")
    private String entitiesJson;

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
    public java.util.UUID getTenantId()              { return tenantId; }
    public void setTenantId(java.util.UUID tenantId) { this.tenantId = tenantId; }
    public String getDatabaseName()                  { return databaseName; }
    public void   setDatabaseName(String db)         { this.databaseName = db; }
    public String getSchemaName()                    { return schemaName; }
    public void   setSchemaName(String s)            { this.schemaName = s; }
    public String getStatus()                        { return status; }
    public void   setStatus(String status)           { this.status = status; }
    public String getLineageKey()                    { return lineageKey; }
    public void   setLineageKey(String lineageKey)   { this.lineageKey = lineageKey; }
    public Long getSupersedesCatalogueId()           { return supersedesCatalogueId; }
    public void setSupersedesCatalogueId(Long id)    { this.supersedesCatalogueId = id; }
    public String getBusinessProfile()               { return businessProfile; }
    public void   setBusinessProfile(String s)       { this.businessProfile = s; }
    public String getGlobalCaveats()                 { return globalCaveats; }
    public void   setGlobalCaveats(String s)         { this.globalCaveats = s; }
    public String getEntitiesJson()                  { return entitiesJson; }
    public void   setEntitiesJson(String s)          { this.entitiesJson = s; }
    public LocalDateTime getCreatedAt()              { return createdAt; }
    public void          setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt()              { return updatedAt; }
    public void          setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
    public List<CatalogueTableEntity> getTables()    { return tables; }
    public void setTables(List<CatalogueTableEntity> t) { this.tables = t; }
}
