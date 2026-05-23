-- ============================================================
-- Phase 2 — Decision Memory Migration
-- ============================================================
-- Logs every user decision (DECLINED / COMPLETED) on an insight card.
-- Used to build a confidence multiplier: agents whose insights are
-- consistently acted on get higher confidence scores over time.
-- ============================================================

CREATE TABLE IF NOT EXISTS decision_memory (
    id           BIGSERIAL    PRIMARY KEY,
    client_id    VARCHAR(255) NOT NULL,
    badge        VARCHAR(20),           -- ALERT | RISK | OPPORTUNITY | INFO
    agent_name   VARCHAR(100),          -- e.g. "Revenue agent"
    impact_level VARCHAR(20),           -- HIGH | MEDIUM | LOW | POSITIVE
    action       VARCHAR(30)  NOT NULL, -- DECLINED | COMPLETED
    changed_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_decision_client_badge
    ON decision_memory(client_id, badge);

CREATE INDEX IF NOT EXISTS idx_decision_client_agent
    ON decision_memory(client_id, agent_name);
