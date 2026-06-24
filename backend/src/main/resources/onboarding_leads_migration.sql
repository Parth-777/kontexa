-- ============================================================
-- Kontexa — Enterprise onboarding leads
-- Run once in pgAdmin against admindb
-- ============================================================

CREATE TABLE IF NOT EXISTS onboarding_leads (
    id              BIGSERIAL    PRIMARY KEY,
    full_name       VARCHAR(255) NOT NULL,
    work_email      VARCHAR(320) NOT NULL,
    company_name    VARCHAR(255) NOT NULL,
    company_domain  VARCHAR(255),
    company_size    VARCHAR(64),
    data_warehouse  VARCHAR(64),
    use_case        TEXT,
    source_page     VARCHAR(255) NOT NULL DEFAULT 'homepage',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_onboarding_leads_email
    ON onboarding_leads(LOWER(work_email));

CREATE INDEX IF NOT EXISTS idx_onboarding_leads_created
    ON onboarding_leads(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_onboarding_leads_domain
    ON onboarding_leads(company_domain);
