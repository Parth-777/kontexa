package com.example.BACKEND.catalogue.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_runs",
        indexes = @Index(name = "idx_agent_runs_client_started", columnList = "client_id, started_at"))
public class AgentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "queries_run", nullable = false)
    private int queriesRun;

    @Column(name = "bytes_scanned", nullable = false)
    private long bytesScanned;

    @Column(name = "budget_exceeded", nullable = false)
    private boolean budgetExceeded;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public int getQueriesRun() { return queriesRun; }
    public void setQueriesRun(int queriesRun) { this.queriesRun = queriesRun; }

    public long getBytesScanned() { return bytesScanned; }
    public void setBytesScanned(long bytesScanned) { this.bytesScanned = bytesScanned; }

    public boolean isBudgetExceeded() { return budgetExceeded; }
    public void setBudgetExceeded(boolean budgetExceeded) { this.budgetExceeded = budgetExceeded; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
