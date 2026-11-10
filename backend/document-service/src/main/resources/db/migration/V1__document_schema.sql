-- =====================================================================
-- document-service, schema "document": original files, byte for byte.
-- On a database that still has the old procurement.quotation_file table, its files are copied here with the same
-- ids (attached to their quotation); the quotation service then keeps only the reference.
-- =====================================================================
CREATE TABLE document (
    id               BIGSERIAL PRIMARY KEY,
    owner_type       VARCHAR(30),
    owner_id         BIGINT,
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    file_name        VARCHAR(255) NOT NULL,
    content_type     VARCHAR(150) NOT NULL,
    file_format      VARCHAR(10)  NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    sha256           VARCHAR(64)  NOT NULL,
    content          BYTEA        NOT NULL,
    uploaded_by_id   BIGINT       NOT NULL,
    uploaded_by_name VARCHAR(60)  NOT NULL,
    uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_document_status CHECK (status IN ('PENDING', 'ATTACHED')),
    CONSTRAINT chk_document_owner CHECK (status = 'PENDING' OR (owner_type IS NOT NULL AND owner_id IS NOT NULL)),
    CONSTRAINT chk_document_format CHECK (file_format IN ('PDF', 'XLSX', 'XLS', 'DOCX')),
    CONSTRAINT chk_document_size CHECK (size_bytes > 0 AND size_bytes = octet_length(content)),
    CONSTRAINT chk_document_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_document_owner ON document (owner_type, owner_id);
CREATE INDEX idx_document_pending ON document (uploaded_at) WHERE status = 'PENDING';

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true' THEN
        ALTER TABLE document ADD CONSTRAINT fk_document_uploaded_by
            FOREIGN KEY (uploaded_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
    END IF;
    IF to_regclass('procurement.quotation_file') IS NOT NULL THEN
        INSERT INTO document (id, owner_type, owner_id, status, file_name, content_type, file_format, size_bytes,
                              sha256, content, uploaded_by_id, uploaded_by_name, uploaded_at)
        SELECT id, 'QUOTATION', quotation_id, 'ATTACHED', file_name, content_type, file_format, size_bytes,
               sha256, content, uploaded_by_id, uploaded_by_name, uploaded_at
        FROM procurement.quotation_file;
        PERFORM setval(pg_get_serial_sequence('document', 'id'), GREATEST((SELECT max(id) FROM document), 1));
    END IF;
END $$;

-- Table of the shared outbox library (this service records no audit events itself, so it stays empty).
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
