package com.sutra.api.enums;

/**
 * How a quotation's data was obtained: by an extraction engine (GROQ, or RULES = the rule-based reader without
 * AI) or entered by a person (MANUAL). GEMINI is kept only for quotations extracted before Groq replaced it.
 */
public enum ExtractionSource {
    GROQ, RULES, MANUAL,
    /** Legacy: extracted by Google Gemini, which is no longer an engine. */
    GEMINI
}
