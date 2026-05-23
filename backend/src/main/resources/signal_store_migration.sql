-- ============================================================
-- Phase 1.2 — Signal Store Migration
-- Run once in pgAdmin Query Tool against your admindb database
-- ============================================================

-- Each row = one detected change in a tenant's data.
-- Agents read this table to decide what to analyse.
-- Every InsightCard will link back to the signal_id(s) that triggered it.

CREATE TABLE IF NOT EXISTS signals (
    signal_id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id    VARCHAR(255) NOT NULL,
    table_name   VARCHAR(255) NOT NULL,
    table_schema VARCHAR(255),
    signal_type  VARCHAR(50)  NOT NULL,  -- METRIC_SHIFT | DISTRIBUTION_CHANGE | TIME_TREND
    column_name  VARCHAR(255),           -- which column changed (null = table-level)
    value        DOUBLE PRECISION,       -- current observed value
    baseline     DOUBLE PRECISION,       -- stored baseline for comparison
    delta_pct    DOUBLE PRECISION,       -- % change from baseline
    raw_payload  TEXT,                   -- JSON snapshot of the rows that triggered this
    significance VARCHAR(10)  NOT NULL,  -- HIGH | MEDIUM | LOW
    detected_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Fetch recent signals for a tenant quickly
CREATE INDEX IF NOT EXISTS idx_signals_client_recent
    ON signals(client_id, detected_at DESC);

-- Deduplication check: has this exact signal fired recently?
CREATE INDEX IF NOT EXISTS idx_signals_dedup
    ON signals(client_id, table_name, signal_type, column_name, detected_at DESC);
