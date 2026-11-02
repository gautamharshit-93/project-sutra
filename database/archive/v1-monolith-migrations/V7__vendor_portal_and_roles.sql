-- =====================================================================
-- V7: Vendor portal and clearer roles.
--   * VIEWER becomes AUDITOR (read-only: requests, comparisons, awards and the audit log).
--   * New VENDOR role: a supplier's login, linked to its vendor record, that sees open RFQs and submits its own
--     quotations. A vendor user never sees other vendors' quotations.
--   * Extraction engines are Gemini and the rule-based reader only.
-- =====================================================================
UPDATE role SET name = 'AUDITOR',
                description = 'Read-only: purchase requests, comparisons, awards and the audit log'
 WHERE name = 'VIEWER';
UPDATE role SET description = 'Full access: master data, users and roles, all purchase requests' WHERE name = 'ADMIN';
UPDATE role SET description = 'Buyer: creates RFQs, reviews and compares vendor quotations' WHERE name = 'PROCUREMENT';
UPDATE role SET description = 'Manager: awards an RFQ to a vendor or rejects it' WHERE name = 'APPROVER';
INSERT INTO role (name, description) VALUES
    ('VENDOR', 'Supplier: sees open RFQs and submits its own quotations');

ALTER TABLE app_user ADD COLUMN vendor_id BIGINT REFERENCES vendor (id) ON DELETE RESTRICT;
CREATE INDEX idx_app_user_vendor ON app_user (vendor_id);

ALTER TABLE quotation DROP CONSTRAINT chk_quotation_source;
ALTER TABLE quotation ADD CONSTRAINT chk_quotation_source
    CHECK (extraction_source IS NULL OR extraction_source IN ('GEMINI', 'RULES', 'MANUAL'));
