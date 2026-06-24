package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant_business_profiles")
public class TenantBusinessProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "industry")
    private String industry;

    @Column(name = "business_model")
    private String businessModel;

    /** JSON array of column names */
    @Column(name = "north_star_metrics", columnDefinition = "TEXT")
    private String northStarMetrics;

    /** JSON array of dimension column names */
    @Column(name = "primary_segments", columnDefinition = "TEXT")
    private String primarySegments;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getBusinessModel() { return businessModel; }
    public void setBusinessModel(String businessModel) { this.businessModel = businessModel; }

    public String getNorthStarMetrics() { return northStarMetrics; }
    public void setNorthStarMetrics(String northStarMetrics) { this.northStarMetrics = northStarMetrics; }

    public String getPrimarySegments() { return primarySegments; }
    public void setPrimarySegments(String primarySegments) { this.primarySegments = primarySegments; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
