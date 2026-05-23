-- ============================================================
-- Phase 1.4 — Insight Cards Migration
-- ============================================================
-- Stores every AI-generated insight card as a persistent record.
-- Each card has a state-machine status so the system can track
-- what the user has acted on, dismissed, or left pending.
--
-- State machine:
--   AWAITING_CONFIRMATION → the default on creation
--   DECLINED              → user dismissed the card
--   COMPLETED             → user acted on it / marked done
--   EXPIRED               → auto-expired after TTL (no longer shown)
-- ============================================================

CREATE TABLE IF NOT EXISTS insight_cards (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        VARCHAR(255) NOT NULL,

    -- Core insight content
    title            TEXT         NOT NULL,
    description      TEXT,
    impact_level     VARCHAR(20),           -- HIGH | MEDIUM | LOW | POSITIVE
    badge            VARCHAR(20),           -- ALERT | RISK | OPPORTUNITY | INFO
    agent_name       VARCHAR(100),
    confidence       INT,

    -- JSON arrays stored as TEXT (parsed at application layer)
    metric_highlights TEXT,                 -- [{label, value}, ...]
    reasons          TEXT,                  -- ["reason 1", "reason 2"]
    strategies       TEXT,                  -- ["strategy 1", "strategy 2"]

    -- State machine
    status           VARCHAR(30)  NOT NULL DEFAULT 'AWAITING_CONFIRMATION',

    -- Timestamps
    generated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMP    NOT NULL,        -- generated_at + 7 days by default
    acted_at         TIMESTAMP                     -- when status changed to DECLINED/COMPLETED

);

CREATE INDEX IF NOT EXISTS idx_insight_client_status
    ON insight_cards(client_id, status);

CREATE INDEX IF NOT EXISTS idx_insight_client_recent
    ON insight_cards(client_id, generated_at DESC);

CREATE INDEX IF NOT EXISTS idx_insight_expires
    ON insight_cards(expires_at)
    WHERE status = 'AWAITING_CONFIRMATION';
