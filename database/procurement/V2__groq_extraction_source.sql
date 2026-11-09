-- V2: Groq (with local Tesseract OCR for scanned PDFs) replaces Gemini as the AI extraction engine.
-- GEMINI stays valid so quotations extracted before the switch keep their history.
ALTER TABLE quotation DROP CONSTRAINT chk_quotation_source;
ALTER TABLE quotation ADD CONSTRAINT chk_quotation_source
    CHECK (extraction_source IS NULL OR extraction_source IN ('GROQ', 'RULES', 'MANUAL', 'GEMINI'));
