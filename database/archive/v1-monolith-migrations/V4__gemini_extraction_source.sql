-- =====================================================================
-- V4: Gemini becomes the extraction engine. Earlier values stay valid for historical rows.
-- =====================================================================
ALTER TABLE quotation DROP CONSTRAINT chk_quotation_source;
ALTER TABLE quotation ADD CONSTRAINT chk_quotation_source
    CHECK (extraction_source IS NULL OR extraction_source IN ('GEMINI', 'MANUAL', 'CLAUDE', 'LOCAL_PARSER'));
