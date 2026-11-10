package com.sutra.api.enums;

/**
 * The extraction engine a user asks for when uploading or retrying a quotation. AUTO tries the engines in the
 * configured order ({@code sutra.extraction.engine-order}: Groq, then the rule-based reader) and moves on when
 * one is unavailable or fails, so a rate-limited free Groq tier never blocks the workflow.
 */
public enum ExtractionEngine {
    AUTO(null, "Automatic (Groq, then rule-based)"),
    GROQ(ExtractionSource.GROQ, "Groq AI (free models, OCR for scans)"),
    RULES(ExtractionSource.RULES, "Rule-based reader (no AI)");

    private final ExtractionSource source;
    private final String label;

    ExtractionEngine(ExtractionSource source, String label) {
        this.source = source;
        this.label = label;
    }

    public ExtractionSource source() {
        return source;
    }

    public String label() {
        return label;
    }
}
