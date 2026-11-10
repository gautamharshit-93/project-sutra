-- =====================================================================
-- The quotation service on its own:
--  * each quotation keeps its RFQ's number (it never changes), so lists need no call to the RFQ service;
--  * the file bytes are kept by the document service: quotation_file keeps a reference (document_id) instead.
--    Files moved by document-service V1 kept their ids, so document_id = id for them.
-- =====================================================================
ALTER TABLE quotation ADD COLUMN request_number VARCHAR(30);
DO $$
BEGIN
    IF to_regclass('rfq.purchase_request') IS NOT NULL THEN
        EXECUTE 'UPDATE quotation q SET request_number = p.request_number
                 FROM rfq.purchase_request p WHERE p.id = q.purchase_request_id';
    END IF;
END $$;
UPDATE quotation SET request_number = 'PR-' || purchase_request_id WHERE request_number IS NULL;
ALTER TABLE quotation ALTER COLUMN request_number SET NOT NULL;

ALTER TABLE quotation_file ADD COLUMN document_id BIGINT;
UPDATE quotation_file SET document_id = id;
ALTER TABLE quotation_file ALTER COLUMN document_id SET NOT NULL;
ALTER TABLE quotation_file ADD CONSTRAINT uq_quotation_file_document UNIQUE (document_id);
ALTER TABLE quotation_file DROP CONSTRAINT IF EXISTS chk_quotation_file_size;
ALTER TABLE quotation_file DROP COLUMN content;
ALTER TABLE quotation_file ADD CONSTRAINT chk_quotation_file_size CHECK (size_bytes > 0);
ALTER TABLE quotation_file ADD CONSTRAINT chk_quotation_file_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$');

DO $$
BEGIN
    IF '${crossSchemaFks}' = 'true' THEN
        ALTER TABLE quotation_file ADD CONSTRAINT fk_quotation_file_document
            FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE RESTRICT;
    END IF;
END $$;
