-- =====================================================================
-- extraction-service, schema "extraction": every reading of a quotation, as a job.
-- =====================================================================
CREATE TABLE extraction_job (
    id              BIGSERIAL PRIMARY KEY,
    quotation_id    BIGINT      NOT NULL,
    engine          VARCHAR(10) NOT NULL,
    preferred_model VARCHAR(100),
    status          VARCHAR(10) NOT NULL DEFAULT 'QUEUED',
    source          VARCHAR(20),
    model_used      VARCHAR(100),
    items_read      INTEGER,
    failure_reason  VARCHAR(1000),
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    CONSTRAINT chk_job_engine CHECK (engine IN ('AUTO', 'GROQ', 'RULES')),
    CONSTRAINT chk_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT chk_job_source CHECK (source IS NULL OR source IN ('GROQ', 'RULES')),
    CONSTRAINT chk_job_items CHECK (items_read IS NULL OR items_read >= 0)
);
-- quotation_id refers to quotation.quotation, which is created after this schema; no foreign key, so a deleted
-- quotation keeps the history of how it was read.
CREATE INDEX idx_job_quotation ON extraction_job (quotation_id, id DESC);
CREATE INDEX idx_job_open ON extraction_job (status) WHERE status IN ('QUEUED', 'RUNNING');

-- Table of the shared outbox library.
CREATE TABLE audit_outbox (
    id                  BIGSERIAL PRIMARY KEY,
    event_id            UUID        NOT NULL UNIQUE,
    entity_type         VARCHAR(50) NOT NULL,
    entity_id           BIGINT,
    purchase_request_id BIGINT,
    action              VARCHAR(50) NOT NULL,
    performed_by        VARCHAR(60) NOT NULL,
    performed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    details             JSONB,
    correlation_id      VARCHAR(64)
);
