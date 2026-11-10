package com.sutra.api.extraction;

import com.sutra.api.enums.ExtractionEngine;
import java.util.List;

public final class ExtractionEngineResponses {

    private ExtractionEngineResponses() {
    }

    /** One reading engine: whether it is configured, its model(s), and whether AUTO tries it. */
    public record EngineInfo(ExtractionEngine engine, String label, String model, List<String> models,
                             boolean available, boolean inAutoOrder) {
    }

    /** Which engines are configured, and the order AUTO tries them in (Groq first, the rule-based reader as backup). */
    public record EnginesResponse(List<EngineInfo> engines, List<ExtractionEngine> autoOrder) {
    }
}
