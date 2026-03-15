-- Kontexa core schema objects

CREATE TABLE IF NOT EXISTS client_catalogues (
    id            BIGSERIAL PRIMARY KEY,
    client_id     TEXT        NOT NULL,
    database_name TEXT,
    schema_name   TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'DRAFT',
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS catalogue_tables (
    id            BIGSERIAL PRIMARY KEY,
    catalogue_id  BIGINT      NOT NULL REFERENCES client_catalogues(id) ON DELETE CASCADE,
    table_name    TEXT        NOT NULL,
    table_schema  TEXT        NOT NULL,
    description   TEXT,
    row_count     BIGINT      DEFAULT 0
);

CREATE TABLE IF NOT EXISTS catalogue_columns (
    id              BIGSERIAL PRIMARY KEY,
    table_id        BIGINT    NOT NULL REFERENCES catalogue_tables(id) ON DELETE CASCADE,
    column_name     TEXT      NOT NULL,
    data_type       TEXT,
    description     TEXT,
    synonyms        TEXT,
    value_meanings  TEXT,
    sample_values   TEXT,
    role            TEXT,
    min_value       TEXT,
    max_value       TEXT,
    avg_value       TEXT,
    is_sensitive    BOOLEAN   NOT NULL DEFAULT false,
    is_enriched     BOOLEAN   NOT NULL DEFAULT false,
    is_skipped      BOOLEAN   NOT NULL DEFAULT false,
    skip_reason     TEXT
);

CREATE TABLE IF NOT EXISTS tenant_credentials (
    id             BIGSERIAL   PRIMARY KEY,
    tenant_id      TEXT        NOT NULL UNIQUE,
    user_id        TEXT        NOT NULL UNIQUE,
    password_hash  TEXT        NOT NULL,
    cloud_db_link  TEXT,
    is_active      BOOLEAN     NOT NULL DEFAULT true,
    created_at     TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP   NOT NULL DEFAULT now()
);
