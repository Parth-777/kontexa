package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores a published projection of an approved catalogue as a JSON blob.
 *
 * Generated automatically when a catalogue is approved.
 * Used at query time — one fast read instead of joining 3 tables.
 *
 * Relational {@code client_catalogues} (+ columns) remain the versioned source of truth.
 * This table currently holds one published snapshot per client (unique on client_id);
 * future evolution can retain history without moving semantics out of relational rows.
 */
@Entity
@Table(name = "catalogue_snapshots")
public class CatalogueSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "catalogue_id", nullable = false)
    private Long catalogueId;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "tenant_id")
    private java.util.UUID tenantId;

    /** The entire enriched catalogue serialized as a JSON string */
    @Column(name = "catalogue_json", nullable = false, columnDefinition = "TEXT")
    private String catalogueJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Getters / Setters ────────────────────────────────────────────

    public Long getId()                        { return id; }
    public Long getCatalogueId()               { return catalogueId; }
    public void setCatalogueId(Long id)        { this.catalogueId = id; }
    public String getClientId()                { return clientId; }
    public void   setClientId(String s)        { this.clientId = s; }
    public java.util.UUID getTenantId()        { return tenantId; }
    public void setTenantId(java.util.UUID id) { this.tenantId = id; }
    public String getCatalogueJson()           { return catalogueJson; }
    public void   setCatalogueJson(String s)   { this.catalogueJson = s; }
    public LocalDateTime getCreatedAt()        { return createdAt; }
    public void setCreatedAt(LocalDateTime t)  { this.createdAt = t; }
}
