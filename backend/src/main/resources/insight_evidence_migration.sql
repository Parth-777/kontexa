-- Migration: add source_columns + raw_evidence to insight_cards
-- Run this once in pgAdmin before restarting the backend.

ALTER TABLE insight_cards
    ADD COLUMN IF NOT EXISTS source_columns TEXT,
    ADD COLUMN IF NOT EXISTS raw_evidence   TEXT;

-- Index helps the evidence lookup by card id (already covered by PK, no extra index needed)
