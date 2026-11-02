-- =====================================================================
-- V5: multi-file quotations and item codes.
-- =====================================================================

-- A quotation may consist of several original files (e.g. the same offer as PDF, Word and Excel, or a quotation
-- plus a price annexure). All files stay linked to the same quotation and are extracted together.
ALTER TABLE quotation_file DROP CONSTRAINT quotation_file_quotation_id_key;
CREATE INDEX idx_quotation_file_quotation ON quotation_file (quotation_id);

-- Item codes (e.g. IT-001) let quoted lines be matched to requested lines exactly.
ALTER TABLE request_item ADD COLUMN item_code VARCHAR(40);
ALTER TABLE quotation_item ADD COLUMN item_code VARCHAR(40);
