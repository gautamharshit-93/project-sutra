package com.sutra.procurement.response;

import com.sutra.procurement.ai.ExtractionEngine;
import com.sutra.procurement.ai.ExtractionEngines;
import java.util.List;

public final class ExtractionEngineResponses {

    private ExtractionEngineResponses() {
    }

    /** Which engines are configured, and the order AUTO tries them in (Groq first, the rule-based reader as backup). */
    public record EnginesResponse(List<ExtractionEngines.EngineInfo> engines, List<ExtractionEngine> autoOrder) {
    }
}
