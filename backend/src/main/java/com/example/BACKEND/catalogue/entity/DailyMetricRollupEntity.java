package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "daily_metric_rollups",
        indexes = {
            @Index(name = "idx_rollup_client_table_date",
                   columnList = "client_id, table_name, metric_date"),
            @Index(name = "idx_rollup_client_table_metric",
                   columnList = "client_id, table_name, metric_name, metric_date")
        })
public class DailyMetricRollupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    @Column(name = "dimension_key")
    private String dimensionKey;

    @Column(name = "dimension_value", length = 1024)
    private String dimensionValue;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    @Column(name = "agg_type", nullable = false, length = 16)
    private String aggType;

    @Column(name = "built_at", nullable = false)
    private LocalDateTime builtAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public LocalDate getMetricDate() { return metricDate; }
    public void setMetricDate(LocalDate metricDate) { this.metricDate = metricDate; }

    public String getDimensionKey() { return dimensionKey; }
    public void setDimensionKey(String dimensionKey) { this.dimensionKey = dimensionKey; }

    public String getDimensionValue() { return dimensionValue; }
    public void setDimensionValue(String dimensionValue) { this.dimensionValue = dimensionValue; }

    public String getMetricName() { return metricName; }
    public void setMetricName(String metricName) { this.metricName = metricName; }

    public double getMetricValue() { return metricValue; }
    public void setMetricValue(double metricValue) { this.metricValue = metricValue; }

    public String getAggType() { return aggType; }
    public void setAggType(String aggType) { this.aggType = aggType; }

    public LocalDateTime getBuiltAt() { return builtAt; }
    public void setBuiltAt(LocalDateTime builtAt) { this.builtAt = builtAt; }
}
