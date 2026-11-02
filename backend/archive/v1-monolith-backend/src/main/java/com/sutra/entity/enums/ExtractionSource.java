package com.sutra.entity.enums;

/**
 * How a quotation's data was obtained: by an extraction engine (GEMINI, or RULES = the rule-based reader without
 * AI) or entered by a person (MANUAL).
 */
public enum ExtractionSource {
    GEMINI, RULES, MANUAL
}
