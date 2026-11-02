package com.sutra.procurement.ai;

import com.sutra.procurement.entity.enums.ExtractionSource;

/**
 * The extraction engine a user asks for when uploading or retrying a quotation. AUTO tries the engines in the
 * configured order ({@code sutra.extraction.engine-order}: Gemini, then the rule-based reader) and moves on when
 * one is unavailable or fails, so a rate-limited free Gemini tier never blocks the workflow.
 */
public enum ExtractionEngine {
    AUTO(null, "Automatic (Gemini, then rule-based)"),
    GEMINI(ExtractionSource.GEMINI, "Google Gemini (free models)"),
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
