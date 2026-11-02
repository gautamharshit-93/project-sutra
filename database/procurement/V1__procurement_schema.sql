-- =====================================================================
-- procurement-service, schema "procurement": RFQs (purchase requests), quotations and their original files,
-- extracted lines, review flags, comparisons and award decisions.
-- Entities, customers, vendors and users belong to other services. They are referenced by id, with the name at
-- the time of use kept alongside (no cross-service foreign keys). Money always uses NUMERIC, never float.
-- =====================================================================
CREATE SEQUENCE purchase_request_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE purchase_request (
    id                BIGSERIAL PRIMARY KEY,
    request_number    VARCHAR(30)  NOT NULL UNIQUE,
    title             VARCHAR(200) NOT NULL,
    description       TEXT,
    entity_id         BIGINT       NOT NULL,
    entity_name       VARCHAR(200) NOT NULL,
    sub_entity_id     BIGINT,
    sub_entity_name   VARCHAR(200),
    customer_id       BIGINT,
    customer_name     VARCHAR(200),
    requested_by_id   BIGINT       NOT NULL,
    requested_by_name VARCHAR(60)  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    currency          VARCHAR(3)   NOT NULL DEFAULT 'INR',
    required_by       DATE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_pr_status CHECK (status IN ('DRAFT', 'OPEN', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_pr_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_pr_sub_entity CHECK ((sub_entity_id IS NULL) = (sub_entity_name IS NULL)),
    CONSTRAINT chk_pr_customer CHECK ((customer_id IS NULL) = (customer_name IS NULL))
);
CREATE INDEX idx_pr_status ON purchase_request (status);
CREATE INDEX idx_pr_entity ON purchase_request (entity_id);
CREATE INDEX idx_pr_sub_entity ON purchase_request (sub_entity_id);
CREATE INDEX idx_pr_customer ON purchase_request (customer_id);
CREATE INDEX idx_pr_requested_by ON purchase_request (requested_by_id);
CREATE INDEX idx_pr_created_at ON purchase_request (created_at DESC);

CREATE TABLE request_item (
    id                  BIGSERIAL PRIMARY KEY,
    purchase_request_id BIGINT        NOT NULL REFERENCES purchase_request (id) ON DELETE CASCADE,
    line_number         INTEGER       NOT NULL,
    item_code           VARCHAR(40),
    description         VARCHAR(500)  NOT NULL,
    quantity            NUMERIC(15,3) NOT NULL,
    unit_of_measure     VARCHAR(30)   NOT NULL,
    specifications      TEXT,
    CONSTRAINT uq_request_item_line UNIQUE (purchase_request_id, line_number),
    CONSTRAINT chk_request_item_qty CHECK (quantity > 0),
    CONSTRAINT chk_request_item_line CHECK (line_number > 0)
);

CREATE TABLE quotation (
    id                    BIGSERIAL PRIMARY KEY,
    purchase_request_id   BIGINT        NOT NULL REFERENCES purchase_request (id) ON DELETE RESTRICT,
    vendor_id             BIGINT        NOT NULL,
    vendor_name           VARCHAR(200)  NOT NULL,
    quotation_number      VARCHAR(60),
    quotation_date        DATE,
    valid_until           DATE,
    currency              VARCHAR(3),
    status                VARCHAR(25)   NOT NULL DEFAULT 'UPLOADED',
    -- Backend-calculated totals (authoritative)
    subtotal              NUMERIC(18,2),
    discount_total        NUMERIC(18,2),
    tax_total             NUMERIC(18,2),
    grand_total           NUMERIC(18,2),
    -- Totals as printed on the vendor document (for arithmetic checks)
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
    processing_attempts   INTEGER       NOT NULL DEFAULT 0,
    last_processed_at     TIMESTAMPTZ,
    uploaded_by_id        BIGINT        NOT NULL,
    uploaded_by_name      VARCHAR(60)   NOT NULL,
    -- True when the vendor submitted it through the vendor portal; false when a buyer uploaded it for the vendor
    submitted_by_vendor   BOOLEAN       NOT NULL DEFAULT FALSE,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_quotation_status CHECK (status IN ('UPLOADED', 'PROCESSING', 'NEEDS_REVIEW', 'READY_FOR_COMPARISON', 'FAILED')),
    CONSTRAINT chk_quotation_source CHECK (extraction_source IS NULL OR extraction_source IN ('GEMINI', 'RULES', 'MANUAL')),
    CONSTRAINT chk_quotation_confidence CHECK (extraction_confidence IS NULL OR extraction_confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_quotation_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_quotation_validity CHECK (valid_until IS NULL OR quotation_date IS NULL OR valid_until >= quotation_date),
    CONSTRAINT chk_quotation_totals CHECK (
        (subtotal IS NULL OR subtotal >= 0) AND (discount_total IS NULL OR discount_total >= 0) AND
        (tax_total IS NULL OR tax_total >= 0) AND (grand_total IS NULL OR grand_total >= 0))
);
CREATE INDEX idx_quotation_pr ON quotation (purchase_request_id);
CREATE INDEX idx_quotation_vendor ON quotation (vendor_id);
CREATE INDEX idx_quotation_status ON quotation (status);
CREATE INDEX idx_quotation_uploaded_by ON quotation (uploaded_by_id);

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

CREATE TABLE quotation_item (
    id                    BIGSERIAL PRIMARY KEY,
    quotation_id          BIGINT        NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
    request_item_id       BIGINT        REFERENCES request_item (id) ON DELETE SET NULL,
    line_number           INTEGER       NOT NULL,
    item_code             VARCHAR(40),
    description           VARCHAR(500)  NOT NULL,
    quantity              NUMERIC(15,3),
    unit_of_measure       VARCHAR(30),
    unit_price            NUMERIC(18,4),
    discount_percent      NUMERIC(7,4),
    discount_amount       NUMERIC(18,2),
    tax_percent           NUMERIC(7,4),
    tax_amount            NUMERIC(18,2),
    line_total            NUMERIC(18,2),   -- backend calculated
    stated_line_total     NUMERIC(18,2),   -- as printed on the document
    match_confidence      NUMERIC(4,3),
    extraction_confidence NUMERIC(4,3),
    manually_edited       BOOLEAN       NOT NULL DEFAULT FALSE,
    notes                 TEXT,
    CONSTRAINT uq_quotation_item_line UNIQUE (quotation_id, line_number),
    CONSTRAINT chk_qi_quantity CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT chk_qi_unit_price CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT chk_qi_discount_pct CHECK (discount_percent IS NULL OR discount_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_qi_tax_pct CHECK (tax_percent IS NULL OR tax_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_qi_amounts CHECK ((discount_amount IS NULL OR discount_amount >= 0) AND (tax_amount IS NULL OR tax_amount >= 0)),
    CONSTRAINT chk_qi_match_conf CHECK (match_confidence IS NULL OR match_confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_qi_extract_conf CHECK (extraction_confidence IS NULL OR extraction_confidence BETWEEN 0 AND 1)
);
CREATE INDEX idx_quotation_item_quotation ON quotation_item (quotation_id);
CREATE INDEX idx_quotation_item_request_item ON quotation_item (request_item_id);

-- Uncertain, conflicting or missing values that a person must look at.
CREATE TABLE quotation_flag (
    id                BIGSERIAL PRIMARY KEY,
    quotation_id      BIGINT       NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
    quotation_item_id BIGINT       REFERENCES quotation_item (id) ON DELETE CASCADE,
    field_name        VARCHAR(60)  NOT NULL,
    severity          VARCHAR(10)  NOT NULL,
    code              VARCHAR(40)  NOT NULL,
    message           VARCHAR(500) NOT NULL,
    resolved          BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_by_id    BIGINT,
    resolved_by_name  VARCHAR(60),
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_flag_severity CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    CONSTRAINT chk_flag_resolution CHECK (resolved = (resolved_at IS NOT NULL))
);
CREATE INDEX idx_quotation_flag_open ON quotation_flag (quotation_id) WHERE NOT resolved;

CREATE TABLE comparison_report (
    id                           BIGSERIAL PRIMARY KEY,
    purchase_request_id          BIGINT      NOT NULL REFERENCES purchase_request (id) ON DELETE RESTRICT,
    report_version               INTEGER     NOT NULL,
    generated_by_id              BIGINT      NOT NULL,
    generated_by_name            VARCHAR(60) NOT NULL,
    generated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    lowest_complete_quotation_id BIGINT      REFERENCES quotation (id) ON DELETE SET NULL,
    details                      JSONB       NOT NULL,
    CONSTRAINT uq_comparison_version UNIQUE (purchase_request_id, report_version),
    CONSTRAINT chk_comparison_version CHECK (report_version > 0)
);

-- Which quotation (and which version of its data) took part in a report.
CREATE TABLE comparison_quotation (
    comparison_report_id BIGINT        NOT NULL REFERENCES comparison_report (id) ON DELETE CASCADE,
    quotation_id         BIGINT        NOT NULL REFERENCES quotation (id) ON DELETE RESTRICT,
    quotation_version    BIGINT        NOT NULL,
    grand_total          NUMERIC(18,2),
    items_quoted         INTEGER       NOT NULL,
    items_missing        INTEGER       NOT NULL,
    price_rank           INTEGER,
    PRIMARY KEY (comparison_report_id, quotation_id)
);
CREATE INDEX idx_comparison_quotation_q ON comparison_quotation (quotation_id);

-- The award decision: APPROVED awards the RFQ to the selected vendor's quotation; REJECTED closes it unawarded.
CREATE TABLE approval (
    id                    BIGSERIAL PRIMARY KEY,
    purchase_request_id   BIGINT      NOT NULL REFERENCES purchase_request (id) ON DELETE RESTRICT,
    comparison_report_id  BIGINT      NOT NULL REFERENCES comparison_report (id) ON DELETE RESTRICT,
    selected_quotation_id BIGINT      REFERENCES quotation (id) ON DELETE RESTRICT,
    decision              VARCHAR(10) NOT NULL,
    reason                TEXT,
    decided_by_id         BIGINT      NOT NULL,
    decided_by_name       VARCHAR(60) NOT NULL,
    decided_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT chk_approval_shape CHECK (
        (decision = 'APPROVED' AND selected_quotation_id IS NOT NULL) OR
        (decision = 'REJECTED' AND reason IS NOT NULL AND length(trim(reason)) > 0))
);
CREATE INDEX idx_approval_pr ON approval (purchase_request_id);

-- Transactional outbox: audit events written with each change, then delivered to the audit service.
CREATE TABLE audit_outbox (
    id                  BIGSERIAL PRIMARY KEY,
    event_id            UUID        NOT NULL UNIQUE,
    entity_type         VARCHAR(50) NOT NULL,
    entity_id           BIGINT,
    purchase_request_id BIGINT,
    action              VARCHAR(50) NOT NULL,
    performed_by        VARCHAR(60) NOT NULL,
    performed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    details             JSONB
);
