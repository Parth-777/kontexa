CREATE TABLE IF NOT EXISTS agent_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       VARCHAR(255) NOT NULL,
    started_at      TIMESTAMP NOT NULL,
    finished_at     TIMESTAMP,
    queries_run     INTEGER NOT NULL DEFAULT 0,
    bytes_scanned   BIGINT NOT NULL DEFAULT 0,
    budget_exceeded BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(32) NOT NULL,
    error_message   TEXT
);

CREATE INDEX IF NOT EXISTS idx_agent_runs_client_started
    ON agent_runs (client_id, started_at DESC);
