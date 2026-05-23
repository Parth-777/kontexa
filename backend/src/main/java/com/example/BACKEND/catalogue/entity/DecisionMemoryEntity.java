package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Records every user decision on an insight card.
 * Used by DecisionMemoryService to compute per-client acceptance rates
 * and adjust confidence scores on future insights.
 */
@Entity
@Table(name = "decision_memory")
public class DecisionMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "badge")
    private String badge;

    @Column(name = "agent_name")
    private String agentName;

    @Column(name = "impact_level")
    private String impactLevel;

    /** DECLINED or COMPLETED */
    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "changed_at")
    private LocalDateTime changedAt;

    public Long getId()                        { return id; }
    public String getClientId()                { return clientId; }
    public void setClientId(String v)          { this.clientId = v; }
    public String getBadge()                   { return badge; }
    public void setBadge(String v)             { this.badge = v; }
    public String getAgentName()               { return agentName; }
    public void setAgentName(String v)         { this.agentName = v; }
    public String getImpactLevel()             { return impactLevel; }
    public void setImpactLevel(String v)       { this.impactLevel = v; }
    public String getAction()                  { return action; }
    public void setAction(String v)            { this.action = v; }
    public LocalDateTime getChangedAt()        { return changedAt; }
    public void setChangedAt(LocalDateTime v)  { this.changedAt = v; }
}
