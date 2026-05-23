-- ============================================================
-- Phase 3.3 — Reports Migration
-- ============================================================
-- Stores AI-generated weekly and monthly narrative reports.
-- Reports summarise all insight cards from the period into a
-- single executive-level narrative the user can read end-to-end.
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_reports (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id    VARCHAR(255) NOT NULL,
    period_type  VARCHAR(20)  NOT NULL,   -- WEEKLY | MONTHLY
    period_label VARCHAR(50)  NOT NULL,   -- e.g. "Week of 2024-03-04" or "March 2024"
    content      TEXT         NOT NULL,   -- full narrative text
    insight_count INT         NOT NULL DEFAULT 0,
    generated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_report_client_recent
    ON agent_reports(client_id, generated_at DESC);
