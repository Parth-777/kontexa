CREATE TABLE IF NOT EXISTS tenant_business_profiles (
    id                  BIGSERIAL PRIMARY KEY,
    client_id           VARCHAR(255) NOT NULL UNIQUE,
    industry            VARCHAR(128),
    business_model      VARCHAR(128),
    north_star_metrics  TEXT,
    primary_segments    TEXT,
    updated_at          TIMESTAMP DEFAULT NOW()
);
