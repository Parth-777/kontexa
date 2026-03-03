-- ============================================================
-- Catalogue Snapshot Table
-- Run this in pgAdmin Query Tool on your admindb database
-- ============================================================

-- One row per approved catalogue.
-- Stores the entire catalogue as a single JSON blob for fast query-time reads.
-- Generated automatically when a catalogue is approved via POST /{id}/approve

CREATE TABLE IF NOT EXISTS catalogue_snapshots (
    id              BIGSERIAL   PRIMARY KEY,
    catalogue_id    BIGINT      NOT NULL REFERENCES client_catalogues(id) ON DELETE CASCADE,
    client_id       TEXT        NOT NULL,
    catalogue_json  TEXT        NOT NULL,   -- full enriched catalogue as one JSON string
    created_at      TIMESTAMP   NOT NULL DEFAULT now()
);

-- One approved snapshot per client at a time
CREATE UNIQUE INDEX IF NOT EXISTS idx_snapshot_client
    ON catalogue_snapshots(client_id);
