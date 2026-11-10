package com.sutra.api.extraction;

import com.sutra.api.enums.ExtractionEngine;

/**
 * What a user asked for when uploading or re-running a quotation: the engine (AUTO by default) and, optionally,
 * which free Groq model to try first. Other Groq models are still tried if that one is rate-limited.
 */
public record ExtractionChoice(ExtractionEngine engine, String model) {

    public static final ExtractionChoice AUTO = new ExtractionChoice(ExtractionEngine.AUTO, null);

    public ExtractionChoice {
        engine = engine == null ? ExtractionEngine.AUTO : engine;
        model = model == null || model.isBlank() ? null : model.trim();
    }

    public static ExtractionChoice of(ExtractionEngine engine) {
        return new ExtractionChoice(engine, null);
    }

    /** "GROQ (openai/gpt-oss-20b)", for audit details. */
    public String describe() {
        return model == null ? engine.name() : engine.name() + " (" + model + ")";
    }
}
