CREATE TABLE IF NOT EXISTS daily_metric_rollups (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        VARCHAR(255) NOT NULL,
    table_name       VARCHAR(255) NOT NULL,
    metric_date      DATE NOT NULL,
    dimension_key    VARCHAR(255),
    dimension_value  VARCHAR(1024),
    metric_name      VARCHAR(255) NOT NULL,
    metric_value     DOUBLE PRECISION NOT NULL,
    agg_type         VARCHAR(16) NOT NULL,
    built_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_rollup_client_table_date
    ON daily_metric_rollups (client_id, table_name, metric_date);

CREATE INDEX IF NOT EXISTS idx_rollup_client_table_metric
    ON daily_metric_rollups (client_id, table_name, metric_name, metric_date);
