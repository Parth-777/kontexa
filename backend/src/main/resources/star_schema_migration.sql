-- ============================================================
-- Phase 1.1 — Star Schema Migration
-- Run once in pgAdmin Query Tool against your admindb database
-- ============================================================

-- 1. Add table_role to catalogue_tables
--    Stores the orchestrator routing hint decided at approval time.
--    Values: MIXED | FACT_DOMINANT | DIMENSION_DOMINANT | UNKNOWN
--    NOTE: classification is at COLUMN level (catalogue_columns.role).
--          tableRole is only a summary routing hint for agents.
ALTER TABLE catalogue_tables
    ADD COLUMN IF NOT EXISTS table_role VARCHAR(30);

CREATE INDEX IF NOT EXISTS idx_catalogue_table_role
    ON catalogue_tables(table_role);

-- 2. New table: catalogue_table_relations
--    Records inferred FACT → DIMENSION join relationships per client
CREATE TABLE IF NOT EXISTS catalogue_table_relations (
    id                      BIGSERIAL PRIMARY KEY,
    client_id               VARCHAR(255) NOT NULL,
    fact_table              VARCHAR(255) NOT NULL,
    fact_table_schema       VARCHAR(255),
    dimension_table         VARCHAR(255) NOT NULL,
    dimension_table_schema  VARCHAR(255),
    join_key                VARCHAR(255) NOT NULL,   -- FK column name on fact table
    confidence              VARCHAR(20),             -- LIKELY | CERTAIN
    created_at              TIMESTAMP,
    CONSTRAINT uq_table_relation
        UNIQUE (client_id, fact_table, dimension_table, join_key)
);

CREATE INDEX IF NOT EXISTS idx_table_relation_client
    ON catalogue_table_relations(client_id);

CREATE INDEX IF NOT EXISTS idx_table_relation_fact
    ON catalogue_table_relations(client_id, fact_table);
