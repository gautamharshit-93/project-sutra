-- =====================================================================
-- V6: Switchable extraction engines - Gemini, an OpenAI-compatible API, and the rule-based reader (no AI).
-- =====================================================================
ALTER TABLE quotation DROP CONSTRAINT chk_quotation_source;
ALTER TABLE quotation ADD CONSTRAINT chk_quotation_source
    CHECK (extraction_source IS NULL
        OR extraction_source IN ('GEMINI', 'OPENAI', 'RULES', 'MANUAL', 'CLAUDE', 'LOCAL_PARSER'));
