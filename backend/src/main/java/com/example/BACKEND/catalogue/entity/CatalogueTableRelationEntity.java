package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records an inferred join between a FACT table and a DIMENSION table
 * within the same client catalogue. Populated by StarSchemaDetector at approval time.
 *
 * Example: fact_orders → dim_product via "product_id"
 */
@Entity
@Table(name = "catalogue_table_relations",
       uniqueConstraints = @UniqueConstraint(
               columnNames = {"client_id", "fact_table", "dimension_table", "join_key"}))
public class CatalogueTableRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "fact_table", nullable = false)
    private String factTable;

    @Column(name = "fact_table_schema")
    private String factTableSchema;

    @Column(name = "dimension_table", nullable = false)
    private String dimensionTable;

    @Column(name = "dimension_table_schema")
    private String dimensionTableSchema;

    /** FK column on the fact table used to join (e.g. "product_id"). */
    @Column(name = "join_key", nullable = false)
    private String joinKey;

    /** LIKELY (name-based inference) | CERTAIN (explicit FK metadata). */
    @Column(name = "confidence")
    private String confidence;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ── Getters / Setters ────────────────────────────────────────────

    public Long          getId()                        { return id; }
    public String        getClientId()                  { return clientId; }
    public void          setClientId(String s)          { this.clientId = s; }
    public String        getFactTable()                 { return factTable; }
    public void          setFactTable(String s)         { this.factTable = s; }
    public String        getFactTableSchema()           { return factTableSchema; }
    public void          setFactTableSchema(String s)   { this.factTableSchema = s; }
    public String        getDimensionTable()            { return dimensionTable; }
    public void          setDimensionTable(String s)    { this.dimensionTable = s; }
    public String        getDimensionTableSchema()      { return dimensionTableSchema; }
    public void          setDimensionTableSchema(String s) { this.dimensionTableSchema = s; }
    public String        getJoinKey()                   { return joinKey; }
    public void          setJoinKey(String s)           { this.joinKey = s; }
    public String        getConfidence()                { return confidence; }
    public void          setConfidence(String s)        { this.confidence = s; }
    public LocalDateTime getCreatedAt()                 { return createdAt; }
    public void          setCreatedAt(LocalDateTime t)  { this.createdAt = t; }
}
