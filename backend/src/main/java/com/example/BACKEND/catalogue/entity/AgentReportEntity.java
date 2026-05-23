package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_reports")
public class AgentReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "period_type", nullable = false)
    private String periodType;   // WEEKLY | MONTHLY

    @Column(name = "period_label", nullable = false)
    private String periodLabel;  // "Week of 2024-03-04" | "March 2024"

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "insight_count")
    private int insightCount;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    public UUID getId()                          { return id; }
    public String getClientId()                  { return clientId; }
    public void setClientId(String v)            { this.clientId = v; }
    public String getPeriodType()                { return periodType; }
    public void setPeriodType(String v)          { this.periodType = v; }
    public String getPeriodLabel()               { return periodLabel; }
    public void setPeriodLabel(String v)         { this.periodLabel = v; }
    public String getContent()                   { return content; }
    public void setContent(String v)             { this.content = v; }
    public int getInsightCount()                 { return insightCount; }
    public void setInsightCount(int v)           { this.insightCount = v; }
    public LocalDateTime getGeneratedAt()        { return generatedAt; }
    public void setGeneratedAt(LocalDateTime v)  { this.generatedAt = v; }
}
