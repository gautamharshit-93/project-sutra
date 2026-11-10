-- =====================================================================
-- comparison-service, schema "comparison": versioned comparison reports and the quotations each one compared.
-- On a database from the single procurement service, the existing tables are MOVED here with their rows and keys;
-- otherwise they are created.
-- =====================================================================
DO $$
BEGIN
    IF to_regclass('procurement.comparison_report') IS NOT NULL THEN
        ALTER TABLE procurement.comparison_report SET SCHEMA comparison;
        ALTER TABLE procurement.comparison_quotation SET SCHEMA comparison;
    ELSE
        CREATE TABLE comparison_report (
            id                           BIGSERIAL PRIMARY KEY,
            purchase_request_id          BIGINT      NOT NULL,
            report_version               INTEGER     NOT NULL,
            generated_by_id              BIGINT      NOT NULL,
            generated_by_name            VARCHAR(60) NOT NULL,
            generated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
            lowest_complete_quotation_id BIGINT,
            details                      JSONB       NOT NULL,
            CONSTRAINT uq_comparison_version UNIQUE (purchase_request_id, report_version),
            CONSTRAINT chk_comparison_version CHECK (report_version > 0)
        );
        CREATE TABLE comparison_quotation (
            comparison_report_id BIGINT  NOT NULL REFERENCES comparison_report (id) ON DELETE CASCADE,
            quotation_id         BIGINT  NOT NULL,
            quotation_version    BIGINT  NOT NULL,
            grand_total          NUMERIC(18,2),
            items_quoted         INTEGER NOT NULL,
            items_missing        INTEGER NOT NULL,
            price_rank           INTEGER,
            PRIMARY KEY (comparison_report_id, quotation_id)
        );
        CREATE INDEX idx_comparison_quotation_q ON comparison_quotation (quotation_id);

        IF '${crossSchemaFks}' = 'true' THEN
            ALTER TABLE comparison_report
                ADD CONSTRAINT comparison_report_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES rfq.purchase_request (id) ON DELETE RESTRICT,
                ADD CONSTRAINT comparison_report_lowest_complete_quotation_id_fkey FOREIGN KEY (lowest_complete_quotation_id) REFERENCES quotation.quotation (id) ON DELETE SET NULL,
                ADD CONSTRAINT fk_comparison_generated_by FOREIGN KEY (generated_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
            ALTER TABLE comparison_quotation
                ADD CONSTRAINT comparison_quotation_quotation_id_fkey FOREIGN KEY (quotation_id) REFERENCES quotation.quotation (id) ON DELETE RESTRICT;
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
