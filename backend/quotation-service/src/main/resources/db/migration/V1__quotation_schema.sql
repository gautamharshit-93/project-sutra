-- =====================================================================
-- quotation-service, schema "quotation": vendor quotations, their lines, review flags and file references.
-- On a database from the single procurement service, the existing tables are MOVED here with their rows, keys,
-- indexes and sequences; otherwise they are created in the same shape. V2 then applies the split's changes.
-- =====================================================================
DO $$
BEGIN
    IF to_regclass('procurement.quotation') IS NOT NULL THEN
        ALTER TABLE procurement.quotation SET SCHEMA quotation;
        ALTER TABLE procurement.quotation_item SET SCHEMA quotation;
        ALTER TABLE procurement.quotation_flag SET SCHEMA quotation;
        ALTER TABLE procurement.quotation_file SET SCHEMA quotation;
    ELSE
        CREATE TABLE quotation (
            id                    BIGSERIAL PRIMARY KEY,
            purchase_request_id   BIGINT       NOT NULL,
            vendor_id             BIGINT       NOT NULL,
            vendor_name           VARCHAR(200) NOT NULL,
            quotation_number      VARCHAR(60),
            quotation_date        DATE,
            valid_until           DATE,
            currency              VARCHAR(3),
            status                VARCHAR(25)  NOT NULL DEFAULT 'UPLOADED',
            subtotal              NUMERIC(18,2),
            discount_total        NUMERIC(18,2),
            tax_total             NUMERIC(18,2),
            grand_total           NUMERIC(18,2),
            stated_subtotal       NUMERIC(18,2),
            stated_tax_total      NUMERIC(18,2),
            stated_grand_total    NUMERIC(18,2),
            delivery_terms        TEXT,
            payment_terms         TEXT,
            warranty_terms        TEXT,
            notes                 TEXT,
            extraction_source     VARCHAR(20),
            extraction_confidence NUMERIC(4,3),
            failure_reason        TEXT,
            processing_attempts   INTEGER      NOT NULL DEFAULT 0,
            last_processed_at     TIMESTAMPTZ,
            uploaded_by_id        BIGINT       NOT NULL,
            uploaded_by_name      VARCHAR(60)  NOT NULL,
            submitted_by_vendor   BOOLEAN      NOT NULL DEFAULT false,
            version               BIGINT       NOT NULL DEFAULT 0,
            created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
            CONSTRAINT uq_quotation_vendor_per_request UNIQUE (purchase_request_id, vendor_id),
            CONSTRAINT chk_quotation_status CHECK (status IN ('UPLOADED', 'PROCESSING', 'NEEDS_REVIEW', 'READY_FOR_COMPARISON', 'FAILED')),
            CONSTRAINT chk_quotation_source CHECK (extraction_source IS NULL OR extraction_source IN ('GROQ', 'RULES', 'MANUAL', 'GEMINI')),
            CONSTRAINT chk_quotation_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
            CONSTRAINT chk_quotation_confidence CHECK (extraction_confidence IS NULL OR (extraction_confidence >= 0 AND extraction_confidence <= 1)),
            CONSTRAINT chk_quotation_validity CHECK (valid_until IS NULL OR quotation_date IS NULL OR valid_until >= quotation_date),
            CONSTRAINT chk_quotation_totals CHECK ((subtotal IS NULL OR subtotal >= 0) AND (discount_total IS NULL OR discount_total >= 0)
                AND (tax_total IS NULL OR tax_total >= 0) AND (grand_total IS NULL OR grand_total >= 0))
        );
        CREATE INDEX idx_quotation_pr ON quotation (purchase_request_id);
        CREATE INDEX idx_quotation_status ON quotation (status);
        CREATE INDEX idx_quotation_vendor ON quotation (vendor_id);
        CREATE INDEX idx_quotation_uploaded_by ON quotation (uploaded_by_id);

        CREATE TABLE quotation_item (
            id                    BIGSERIAL PRIMARY KEY,
            quotation_id          BIGINT       NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
            request_item_id       BIGINT,
            line_number           INTEGER      NOT NULL,
            item_code             VARCHAR(40),
            description           VARCHAR(500) NOT NULL,
            quantity              NUMERIC(15,3),
            unit_of_measure       VARCHAR(30),
            unit_price            NUMERIC(18,4),
            discount_percent      NUMERIC(7,4),
            discount_amount       NUMERIC(18,2),
            tax_percent           NUMERIC(7,4),
            tax_amount            NUMERIC(18,2),
            line_total            NUMERIC(18,2),
            stated_line_total     NUMERIC(18,2),
            match_confidence      NUMERIC(4,3),
            extraction_confidence NUMERIC(4,3),
            manually_edited       BOOLEAN      NOT NULL DEFAULT false,
            notes                 TEXT,
            CONSTRAINT uq_quotation_item_line UNIQUE (quotation_id, line_number),
            CONSTRAINT chk_qi_quantity CHECK (quantity IS NULL OR quantity > 0),
            CONSTRAINT chk_qi_unit_price CHECK (unit_price IS NULL OR unit_price >= 0),
            CONSTRAINT chk_qi_discount_pct CHECK (discount_percent IS NULL OR (discount_percent >= 0 AND discount_percent <= 100)),
            CONSTRAINT chk_qi_tax_pct CHECK (tax_percent IS NULL OR (tax_percent >= 0 AND tax_percent <= 100)),
            CONSTRAINT chk_qi_amounts CHECK ((discount_amount IS NULL OR discount_amount >= 0) AND (tax_amount IS NULL OR tax_amount >= 0)),
            CONSTRAINT chk_qi_match_conf CHECK (match_confidence IS NULL OR (match_confidence >= 0 AND match_confidence <= 1)),
            CONSTRAINT chk_qi_extract_conf CHECK (extraction_confidence IS NULL OR (extraction_confidence >= 0 AND extraction_confidence <= 1))
        );
        CREATE INDEX idx_quotation_item_quotation ON quotation_item (quotation_id);
        CREATE INDEX idx_quotation_item_request_item ON quotation_item (request_item_id);

        CREATE TABLE quotation_flag (
            id                BIGSERIAL PRIMARY KEY,
            quotation_id      BIGINT       NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
            quotation_item_id BIGINT       REFERENCES quotation_item (id) ON DELETE CASCADE,
            field_name        VARCHAR(60)  NOT NULL,
            severity          VARCHAR(10)  NOT NULL,
            code              VARCHAR(40)  NOT NULL,
            message           VARCHAR(500) NOT NULL,
            resolved          BOOLEAN      NOT NULL DEFAULT false,
            resolved_by_id    BIGINT,
            resolved_by_name  VARCHAR(60),
            resolved_at       TIMESTAMPTZ,
            created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
            CONSTRAINT chk_flag_severity CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
            CONSTRAINT chk_flag_resolution CHECK (resolved = (resolved_at IS NOT NULL))
        );
        CREATE INDEX idx_quotation_flag_open ON quotation_flag (quotation_id) WHERE NOT resolved;

        CREATE TABLE quotation_file (
            id               BIGSERIAL PRIMARY KEY,
            quotation_id     BIGINT       NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
            file_name        VARCHAR(255) NOT NULL,
            content_type     VARCHAR(150) NOT NULL,
            file_format      VARCHAR(10)  NOT NULL,
            size_bytes       BIGINT       NOT NULL,
            sha256           VARCHAR(64)  NOT NULL,
            content          BYTEA        NOT NULL,
            uploaded_by_id   BIGINT       NOT NULL,
            uploaded_by_name VARCHAR(60)  NOT NULL,
            uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
            CONSTRAINT chk_quotation_file_format CHECK (file_format IN ('PDF', 'XLSX', 'XLS', 'DOCX')),
            CONSTRAINT chk_quotation_file_size CHECK (size_bytes > 0 AND size_bytes = octet_length(content))
        );
        CREATE INDEX idx_quotation_file_quotation ON quotation_file (quotation_id);
        CREATE INDEX idx_quotation_file_sha ON quotation_file (sha256);

        IF '${crossSchemaFks}' = 'true' THEN
            ALTER TABLE quotation
                ADD CONSTRAINT quotation_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES rfq.purchase_request (id) ON DELETE RESTRICT,
                ADD CONSTRAINT fk_quotation_vendor FOREIGN KEY (vendor_id) REFERENCES masterdata.vendor (id) ON DELETE RESTRICT,
                ADD CONSTRAINT fk_quotation_uploaded_by FOREIGN KEY (uploaded_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
            ALTER TABLE quotation_item
                ADD CONSTRAINT quotation_item_request_item_id_fkey FOREIGN KEY (request_item_id) REFERENCES rfq.request_item (id) ON DELETE SET NULL;
            ALTER TABLE quotation_flag
                ADD CONSTRAINT fk_quotation_flag_resolved_by FOREIGN KEY (resolved_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
            ALTER TABLE quotation_file
                ADD CONSTRAINT fk_quotation_file_uploaded_by FOREIGN KEY (uploaded_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
        END IF;
    END IF;
END $$;

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
