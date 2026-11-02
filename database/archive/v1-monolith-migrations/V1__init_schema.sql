-- =====================================================================
-- Project SUTRA - Vendor Quotation Management System
-- V1: core schema
--   * Every monetary amount uses NUMERIC (never float).
--   * Status columns are guarded by CHECK constraints that mirror the
--     Java enums in com.sutra.entity.enums.
--   * Original files and extracted data hang off the same quotation row.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Users and roles
-- ---------------------------------------------------------------------
CREATE TABLE role (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255)
);

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(60)  NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    full_name     VARCHAR(150) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_app_user_username CHECK (username ~ '^[A-Za-z0-9._-]{3,60}$')
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_role_role ON user_role (role_id);

-- ---------------------------------------------------------------------
-- Master data: entities, sub-entities, customers, vendors
-- ---------------------------------------------------------------------
CREATE TABLE business_entity (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(30)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    address     TEXT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_business_entity_name ON business_entity (lower(name));

CREATE TABLE sub_entity (
    id          BIGSERIAL PRIMARY KEY,
    entity_id   BIGINT       NOT NULL REFERENCES business_entity (id) ON DELETE RESTRICT,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sub_entity_code UNIQUE (entity_id, code)
);
CREATE INDEX idx_sub_entity_entity ON sub_entity (entity_id);

CREATE TABLE customer (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(30)  NOT NULL UNIQUE,
    name           VARCHAR(200) NOT NULL,
    entity_id      BIGINT       REFERENCES business_entity (id) ON DELETE RESTRICT,
    contact_person VARCHAR(150),
    email          VARCHAR(255),
    phone          VARCHAR(40),
    address        TEXT,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_name ON customer (lower(name));
CREATE INDEX idx_customer_entity ON customer (entity_id);

CREATE TABLE vendor (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(30)  NOT NULL UNIQUE,
    name           VARCHAR(200) NOT NULL,
    contact_person VARCHAR(150),
    email          VARCHAR(255),
    phone          VARCHAR(40),
    address        TEXT,
    tax_number     VARCHAR(50),
    website        VARCHAR(255),
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_vendor_name ON vendor (lower(name));

-- ---------------------------------------------------------------------
-- Purchase requests and their items
-- ---------------------------------------------------------------------
CREATE TABLE purchase_request (
    id             BIGSERIAL PRIMARY KEY,
    request_number VARCHAR(30)  NOT NULL UNIQUE,
    title          VARCHAR(200) NOT NULL,
    description    TEXT,
    entity_id      BIGINT       NOT NULL REFERENCES business_entity (id) ON DELETE RESTRICT,
    sub_entity_id  BIGINT       REFERENCES sub_entity (id) ON DELETE RESTRICT,
    customer_id    BIGINT       REFERENCES customer (id) ON DELETE RESTRICT,
    requested_by   BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    currency       VARCHAR(3)      NOT NULL DEFAULT 'INR',
    required_by    DATE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_pr_status CHECK (status IN ('DRAFT', 'OPEN', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_pr_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX idx_pr_status ON purchase_request (status);
CREATE INDEX idx_pr_entity ON purchase_request (entity_id);
CREATE INDEX idx_pr_requested_by ON purchase_request (requested_by);
CREATE INDEX idx_pr_created_at ON purchase_request (created_at DESC);

CREATE TABLE request_item (
    id                  BIGSERIAL PRIMARY KEY,
    purchase_request_id BIGINT        NOT NULL REFERENCES purchase_request (id) ON DELETE CASCADE,
    line_number         INTEGER       NOT NULL,
    description         VARCHAR(500)  NOT NULL,
    quantity            NUMERIC(15,3) NOT NULL,
    unit_of_measure     VARCHAR(30)   NOT NULL,
    specifications      TEXT,
    CONSTRAINT uq_request_item_line UNIQUE (purchase_request_id, line_number),
    CONSTRAINT chk_request_item_qty CHECK (quantity > 0),
    CONSTRAINT chk_request_item_line CHECK (line_number > 0)
);

-- ---------------------------------------------------------------------
-- Quotations, original files, extracted items and review flags
-- ---------------------------------------------------------------------
CREATE TABLE quotation (
    id                    BIGSERIAL PRIMARY KEY,
    purchase_request_id   BIGINT        NOT NULL REFERENCES purchase_request (id) ON DELETE RESTRICT,
    vendor_id             BIGINT        NOT NULL REFERENCES vendor (id) ON DELETE RESTRICT,
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
    uploaded_by           BIGINT        NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_quotation_status CHECK (status IN ('UPLOADED', 'PROCESSING', 'NEEDS_REVIEW', 'READY_FOR_COMPARISON', 'FAILED')),
    CONSTRAINT chk_quotation_source CHECK (extraction_source IS NULL OR extraction_source IN ('CLAUDE', 'LOCAL_PARSER', 'MANUAL')),
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

CREATE TABLE quotation_file (
    id           BIGSERIAL PRIMARY KEY,
    quotation_id BIGINT       NOT NULL UNIQUE REFERENCES quotation (id) ON DELETE CASCADE,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    file_format  VARCHAR(10)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)     NOT NULL,
    content      BYTEA        NOT NULL,
    uploaded_by  BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    uploaded_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_quotation_file_format CHECK (file_format IN ('PDF', 'XLSX', 'XLS', 'DOCX')),
    CONSTRAINT chk_quotation_file_size CHECK (size_bytes > 0 AND size_bytes = octet_length(content))
);
CREATE INDEX idx_quotation_file_sha ON quotation_file (sha256);

CREATE TABLE quotation_item (
    id                    BIGSERIAL PRIMARY KEY,
    quotation_id          BIGINT        NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
    request_item_id       BIGINT        REFERENCES request_item (id) ON DELETE SET NULL,
    line_number           INTEGER       NOT NULL,
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

-- Uncertain, conflicting or missing values that a human must look at.
CREATE TABLE quotation_flag (
    id                BIGSERIAL PRIMARY KEY,
    quotation_id      BIGINT       NOT NULL REFERENCES quotation (id) ON DELETE CASCADE,
    quotation_item_id BIGINT       REFERENCES quotation_item (id) ON DELETE CASCADE,
    field_name        VARCHAR(60)  NOT NULL,
    severity          VARCHAR(10)  NOT NULL,
    code              VARCHAR(40)  NOT NULL,
    message           VARCHAR(500) NOT NULL,
    resolved          BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_by       BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_flag_severity CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    CONSTRAINT chk_flag_resolution CHECK (resolved = (resolved_at IS NOT NULL))
);
CREATE INDEX idx_quotation_flag_open ON quotation_flag (quotation_id) WHERE NOT resolved;

-- ---------------------------------------------------------------------
-- Comparison reports
-- ---------------------------------------------------------------------
CREATE TABLE comparison_report (
    id                       BIGSERIAL PRIMARY KEY,
    purchase_request_id      BIGINT      NOT NULL REFERENCES purchase_request (id) ON DELETE RESTRICT,
    report_version           INTEGER     NOT NULL,
    generated_by             BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    generated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    lowest_complete_quotation_id BIGINT  REFERENCES quotation (id) ON DELETE SET NULL,
    details                  JSONB       NOT NULL,
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

-- ---------------------------------------------------------------------
-- Approvals and audit trail
-- ---------------------------------------------------------------------
CREATE TABLE approval (
    id                    BIGSERIAL PRIMARY KEY,
    purchase_request_id   BIGINT      NOT NULL REFERENCES purchase_request (id) ON DELETE RESTRICT,
    comparison_report_id  BIGINT      NOT NULL REFERENCES comparison_report (id) ON DELETE RESTRICT,
    selected_quotation_id BIGINT      REFERENCES quotation (id) ON DELETE RESTRICT,
    decision              VARCHAR(10) NOT NULL,
    reason                TEXT,
    decided_by            BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    decided_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT chk_approval_shape CHECK (
        (decision = 'APPROVED' AND selected_quotation_id IS NOT NULL) OR
        (decision = 'REJECTED' AND reason IS NOT NULL AND length(trim(reason)) > 0))
);
CREATE INDEX idx_approval_pr ON approval (purchase_request_id);

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    BIGINT,
    action       VARCHAR(50) NOT NULL,
    performed_by VARCHAR(60) NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    details      JSONB
);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_performed_at ON audit_log (performed_at DESC);
CREATE INDEX idx_audit_performed_by ON audit_log (performed_by);
