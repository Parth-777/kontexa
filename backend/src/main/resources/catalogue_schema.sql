-- ============================================================
-- Kontexa Catalogue Schema
-- Run this once in pgAdmin Query Tool on your admindb database
-- ============================================================

-- 1. Top-level catalogue per client
CREATE TABLE IF NOT EXISTS client_catalogues (
    id            BIGSERIAL PRIMARY KEY,
    client_id     TEXT        NOT NULL,
    database_name TEXT,
    schema_name   TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'DRAFT',  -- DRAFT, APPROVED, REJECTED
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);

-- 2. One row per table discovered in the client's database
CREATE TABLE IF NOT EXISTS catalogue_tables (
    id            BIGSERIAL PRIMARY KEY,
    catalogue_id  BIGINT      NOT NULL REFERENCES client_catalogues(id) ON DELETE CASCADE,
    table_name    TEXT        NOT NULL,
    table_schema  TEXT        NOT NULL,
    description   TEXT,
    row_count     BIGINT      DEFAULT 0
);

-- 3. One row per column discovered in each table
CREATE TABLE IF NOT EXISTS catalogue_columns (
    id              BIGSERIAL PRIMARY KEY,
    table_id        BIGINT    NOT NULL REFERENCES catalogue_tables(id) ON DELETE CASCADE,
    column_name     TEXT      NOT NULL,
    data_type       TEXT,
    description     TEXT,
    synonyms        TEXT,    -- stored as JSON array string e.g. ["revenue","sales","amount"]
    value_meanings  TEXT,    -- stored as JSON object string e.g. {"IN":"India","US":"USA"}
    sample_values   TEXT,    -- stored as JSON array string e.g. ["page_view","purchase"]
    role            TEXT,    -- dimension | metric | filter | timestamp | identifier | freetext
    min_value       TEXT,
    max_value       TEXT,
    avg_value       TEXT,
    is_sensitive    BOOLEAN   NOT NULL DEFAULT false,
    is_enriched     BOOLEAN   NOT NULL DEFAULT false,
    is_skipped      BOOLEAN   NOT NULL DEFAULT false,
    skip_reason     TEXT
);
