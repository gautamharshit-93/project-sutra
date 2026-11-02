package com.sutra.procurement.ai;

/**
 * What a user asked for when uploading or re-running a quotation: the engine (AUTO by default) and, optionally,
 * which free Gemini model to try first. Other Gemini models are still tried if that one is rate-limited.
 */
public record ExtractionChoice(ExtractionEngine engine, String geminiModel) {

    public static final ExtractionChoice AUTO = new ExtractionChoice(ExtractionEngine.AUTO, null);

    public ExtractionChoice {
        engine = engine == null ? ExtractionEngine.AUTO : engine;
        geminiModel = geminiModel == null || geminiModel.isBlank() ? null : geminiModel.trim();
    }

    public static ExtractionChoice of(ExtractionEngine engine) {
        return new ExtractionChoice(engine, null);
    }

    /** "GEMINI (gemini-3.5-flash-lite)", for audit details. */
    public String describe() {
        return geminiModel == null ? engine.name() : engine.name() + " (" + geminiModel + ")";
    }
}
