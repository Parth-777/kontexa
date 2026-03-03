package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores the entire approved catalogue as a single JSON blob.
 *
 * Generated automatically when a catalogue is approved.
 * Used at query time — one fast read instead of joining 3 tables.
 *
 * One row per client (enforced by unique index on client_id).
 * Approving a new catalogue replaces the previous snapshot.
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
    public String getCatalogueJson()           { return catalogueJson; }
    public void   setCatalogueJson(String s)   { this.catalogueJson = s; }
    public LocalDateTime getCreatedAt()        { return createdAt; }
    public void setCreatedAt(LocalDateTime t)  { this.createdAt = t; }
}
