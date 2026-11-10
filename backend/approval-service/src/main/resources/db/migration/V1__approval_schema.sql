-- =====================================================================
-- approval-service, schema "approval": award / reject decisions. Never changed after they are recorded.
-- On a database from the single procurement service, the existing table is MOVED here with its rows and keys;
-- otherwise it is created. V2 adds what a decision keeps of the comparison and the awarded vendor.
-- =====================================================================
DO $$
BEGIN
    IF to_regclass('procurement.approval') IS NOT NULL THEN
        ALTER TABLE procurement.approval SET SCHEMA approval;
    ELSE
        CREATE TABLE approval (
            id                    BIGSERIAL PRIMARY KEY,
            purchase_request_id   BIGINT      NOT NULL,
            comparison_report_id  BIGINT      NOT NULL,
            selected_quotation_id BIGINT,
            decision              VARCHAR(10) NOT NULL,
            reason                TEXT,
            decided_by_id         BIGINT      NOT NULL,
            decided_by_name       VARCHAR(60) NOT NULL,
            decided_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT chk_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
            CONSTRAINT chk_approval_shape CHECK ((decision = 'APPROVED' AND selected_quotation_id IS NOT NULL)
                OR (decision = 'REJECTED' AND reason IS NOT NULL AND length(trim(reason)) > 0))
        );
        CREATE INDEX idx_approval_pr ON approval (purchase_request_id);

        IF '${crossSchemaFks}' = 'true' THEN
            ALTER TABLE approval
                ADD CONSTRAINT approval_purchase_request_id_fkey FOREIGN KEY (purchase_request_id) REFERENCES rfq.purchase_request (id) ON DELETE RESTRICT,
                ADD CONSTRAINT approval_comparison_report_id_fkey FOREIGN KEY (comparison_report_id) REFERENCES comparison.comparison_report (id) ON DELETE RESTRICT,
                ADD CONSTRAINT approval_selected_quotation_id_fkey FOREIGN KEY (selected_quotation_id) REFERENCES quotation.quotation (id) ON DELETE RESTRICT,
                ADD CONSTRAINT fk_approval_decided_by FOREIGN KEY (decided_by_id) REFERENCES auth.app_user (id) ON DELETE RESTRICT;
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
