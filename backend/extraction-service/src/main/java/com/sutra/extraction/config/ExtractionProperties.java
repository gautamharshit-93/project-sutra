package com.sutra.extraction.config;

import com.sutra.api.enums.ExtractionEngine;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of {@code sutra.extraction.*}: which engines AUTO tries and in what order, how many extractions run
 * at once, and the free Groq models users can switch between (also tried in turn when one is rate-limited).
 */
@ConfigurationProperties(prefix = "sutra.extraction")
public record ExtractionProperties(List<ExtractionEngine> engineOrder, int concurrency,
                                   List<String> groqFallbackModels) {

    public ExtractionProperties {
        engineOrder = engineOrder == null || engineOrder.isEmpty()
                ? List.of(ExtractionEngine.GROQ, ExtractionEngine.RULES)
                : engineOrder.stream().filter(e -> e != ExtractionEngine.AUTO).distinct().toList();
        concurrency = Math.max(1, concurrency);
        groqFallbackModels = groqFallbackModels == null ? List.of()
                : groqFallbackModels.stream().filter(m -> m != null && !m.isBlank()).map(String::trim).toList();
    }

    /** Defaults: Groq first, then the rule-based reader; one extraction at a time. */
    public static ExtractionProperties defaults() {
        return new ExtractionProperties(null, 1, null);
    }
}
