package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One detected change in a tenant's data source.
 *
 * Signals are the atomic unit of the agentic pipeline:
 *   data change → Signal → Agent evaluation → InsightCard
 *
 * Every InsightCard stores the signal_id(s) that triggered it,
 * giving full traceability from insight back to raw data.
 */
@Entity
@Table(name = "signals")
public class SignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "signal_id", updatable = false, nullable = false)
    private UUID signalId;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "table_schema")
    private String tableSchema;

    /**
     * METRIC_SHIFT        — a numeric column's value changed significantly vs baseline
     * DISTRIBUTION_CHANGE — a categorical column's top-value share shifted
     * TIME_TREND          — monthly record volume changed significantly
     */
    @Column(name = "signal_type", nullable = false)
    private String signalType;

    /** Column that changed. Null for table-level signals (e.g. TIME_TREND). */
    @Column(name = "column_name")
    private String columnName;

    /** Current observed value (metric avg, top-category %, latest period count). */
    @Column(name = "value")
    private Double value;

    /** Rolling baseline the current value is compared against. */
    @Column(name = "baseline")
    private Double baseline;

    /** ((value − baseline) / |baseline|) × 100 */
    @Column(name = "delta_pct")
    private Double deltaPct;

    /** JSON snapshot of the rows/aggregates that produced this signal. */
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    /** HIGH (≥10% delta) | MEDIUM (5–10%) | LOW (<5%, baseline-only) */
    @Column(name = "significance", nullable = false)
    private String significance;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public UUID          getSignalId()             { return signalId; }
    public void          setSignalId(UUID u)       { this.signalId = u; }
    public String        getClientId()             { return clientId; }
    public void          setClientId(String s)     { this.clientId = s; }
    public String        getTableName()            { return tableName; }
    public void          setTableName(String s)    { this.tableName = s; }
    public String        getTableSchema()          { return tableSchema; }
    public void          setTableSchema(String s)  { this.tableSchema = s; }
    public String        getSignalType()           { return signalType; }
    public void          setSignalType(String s)   { this.signalType = s; }
    public String        getColumnName()           { return columnName; }
    public void          setColumnName(String s)   { this.columnName = s; }
    public Double        getValue()                { return value; }
    public void          setValue(Double d)        { this.value = d; }
    public Double        getBaseline()             { return baseline; }
    public void          setBaseline(Double d)     { this.baseline = d; }
    public Double        getDeltaPct()             { return deltaPct; }
    public void          setDeltaPct(Double d)     { this.deltaPct = d; }
    public String        getRawPayload()           { return rawPayload; }
    public void          setRawPayload(String s)   { this.rawPayload = s; }
    public String        getSignificance()         { return significance; }
    public void          setSignificance(String s) { this.significance = s; }
    public LocalDateTime getDetectedAt()           { return detectedAt; }
    public void          setDetectedAt(LocalDateTime t) { this.detectedAt = t; }
}
