-- =====================================================================
-- auth-service, schema "auth": users, roles and their assignment.
-- The first administrator is created by the service on start-up (no password hash lives here).
-- =====================================================================
CREATE TABLE role (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255),
    CONSTRAINT chk_role_name CHECK (name IN ('ADMIN', 'PROCUREMENT', 'APPROVER', 'AUDITOR', 'VENDOR'))
);

INSERT INTO role (name, description) VALUES
    ('ADMIN',       'Full access: master data, users and roles, all purchase requests'),
    ('PROCUREMENT', 'Buyer: creates RFQs, reviews and compares vendor quotations'),
    ('APPROVER',    'Manager: awards an RFQ to a vendor or rejects it'),
    ('AUDITOR',     'Read-only: purchase requests, comparisons, awards and the audit log'),
    ('VENDOR',      'Supplier: sees open RFQs and submits its own quotations');

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(60)  NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    full_name     VARCHAR(150) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    -- The supplier a VENDOR user logs in for (a masterdata-service vendor; no cross-service foreign key)
    vendor_id     BIGINT,
    vendor_name   VARCHAR(200),
    -- Raised on every change to roles, status or password; the gateway refuses tokens with an older version
    token_version BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_app_user_username CHECK (username ~ '^[A-Za-z0-9._-]{3,60}$'),
    CONSTRAINT chk_app_user_vendor CHECK ((vendor_id IS NULL) = (vendor_name IS NULL))
);
CREATE INDEX idx_app_user_vendor ON app_user (vendor_id);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_role_role ON user_role (role_id);

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
