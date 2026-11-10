-- =====================================================================
-- rfq-service, schema "rfq": purchase requests (RFQs) and their items.
-- On a database from the single procurement service, the existing tables are MOVED here with their rows, keys,
-- indexes and sequences (ALTER TABLE ... SET SCHEMA); otherwise they are created.
-- =====================================================================
DO $$
BEGIN
    IF to_regclass('procurement.purchase_request') IS NOT NULL THEN
        ALTER TABLE procurement.purchase_request SET SCHEMA rfq;
        ALTER TABLE procurement.request_item SET SCHEMA rfq;
        ALTER SEQUENCE procurement.purchase_request_number_seq SET SCHEMA rfq;
        -- events the old service had not delivered yet are delivered by this one
        IF to_regclass('procurement.audit_outbox') IS NOT NULL THEN
            ALTER TABLE procurement.audit_outbox SET SCHEMA rfq;
        END IF;
    ELSE
        CREATE SEQUENCE purchase_request_number_seq START 1;

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
            quotations_due    DATE,
            created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
            CONSTRAINT chk_pr_status CHECK (status IN ('DRAFT', 'OPEN', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED')),
            CONSTRAINT chk_pr_currency CHECK (currency ~ '^[A-Z]{3}$'),
            CONSTRAINT chk_pr_sub_entity CHECK ((sub_entity_id IS NULL) = (sub_entity_name IS NULL)),
            CONSTRAINT chk_pr_customer CHECK ((customer_id IS NULL) = (customer_name IS NULL)),
            CONSTRAINT chk_pr_quotations_due CHECK (quotations_due IS NULL OR required_by IS NULL OR quotations_due <= required_by)
        );
        CREATE INDEX idx_pr_status ON purchase_request (status);
        CREATE INDEX idx_pr_created_at ON purchase_request (created_at DESC);
        CREATE INDEX idx_pr_entity ON purchase_request (entity_id);
        CREATE INDEX idx_pr_sub_entity ON purchase_request (sub_entity_id);
        CREATE INDEX idx_pr_customer ON purchase_request (customer_id);
        CREATE INDEX idx_pr_requested_by ON purchase_request (requested_by_id);

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
            CONSTRAINT chk_request_item_line CHECK (line_number > 0),
            CONSTRAINT chk_request_item_qty CHECK (quantity > 0)
        );

        IF '${crossSchemaFks}' = 'true' THEN
            ALTER TABLE purchase_request
                ADD CONSTRAINT fk_pr_entity FOREIGN KEY (entity_id) REFERENCES masterdata.business_entity (id) ON DELETE RESTRICT,
                ADD CONSTRAINT fk_pr_sub_entity FOREIGN KEY (sub_entity_id) REFERENCES masterdata.sub_entity (id) ON DELETE RESTRICT,
                ADD CONSTRAINT fk_pr_customer FOREIGN KEY (customer_id) REFERENCES masterdata.customer (id) ON DELETE RESTRICT,
                ADD CONSTRAINT fk_pr_requested_by FOREIGN KEY (requested_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
        END IF;
    END IF;

    IF to_regclass('audit_outbox') IS NULL THEN
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
    END IF;
END $$;
