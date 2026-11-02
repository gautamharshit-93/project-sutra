package com.sutra.ai;

import com.sutra.entity.enums.ExtractionSource;

/**
 * The extraction engine a user asks for when uploading or retrying a quotation. AUTO tries the engines in the
 * configured order ({@code sutra.extraction.engine-order}) and moves on when one is unavailable or fails, so a
 * rate-limited free AI tier never blocks the workflow: the rule-based engine needs no AI at all.
 */
public enum ExtractionEngine {
    AUTO(null, "Automatic (first available engine)"),
    GEMINI(ExtractionSource.GEMINI, "Google Gemini (free tier)"),
    OPENAI(ExtractionSource.OPENAI, "OpenAI-compatible (Groq, OpenRouter, Ollama, OpenAI)"),
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
