-- =====================================================================
-- masterdata-service, schema "masterdata": entities, sub-entities, customers and vendors.
-- =====================================================================
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
