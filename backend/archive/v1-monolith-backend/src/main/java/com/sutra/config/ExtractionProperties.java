package com.sutra.config;

import com.sutra.ai.ExtractionEngine;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of {@code sutra.extraction.*}: which engines AUTO tries and in what order, how many extractions run
 * at once, the free Gemini models to switch to when one is rate-limited, and the OpenAI-compatible engine.
 */
@ConfigurationProperties(prefix = "sutra.extraction")
public record ExtractionProperties(List<ExtractionEngine> engineOrder, int concurrency,
                                   List<String> geminiFallbackModels, OpenAi openai) {

    public ExtractionProperties {
        engineOrder = engineOrder == null || engineOrder.isEmpty()
                ? List.of(ExtractionEngine.GEMINI, ExtractionEngine.OPENAI, ExtractionEngine.RULES)
                : engineOrder.stream().filter(e -> e != ExtractionEngine.AUTO).distinct().toList();
        concurrency = Math.max(1, concurrency);
        geminiFallbackModels = geminiFallbackModels == null ? List.of()
                : geminiFallbackModels.stream().filter(m -> m != null && !m.isBlank()).map(String::trim).toList();
        openai = openai == null ? new OpenAi("", "https://api.groq.com/openai/v1", "", 16000, 180) : openai;
    }

    /** Defaults: Gemini first, then OpenAI-compatible, then rules; one extraction at a time. */
    public static ExtractionProperties defaults() {
        return new ExtractionProperties(null, 1, null, null);
    }

    /**
     * Any server speaking the OpenAI chat-completions API: Groq or OpenRouter (free tiers), a local Ollama
     * ({@code http://localhost:11434/v1}, no key needed) or OpenAI itself. The key is never logged.
     */
    public record OpenAi(String apiKey, String baseUrl, String model, int maxOutputTokens, int timeoutSeconds) {

        public boolean isConfigured() {
            return model != null && !model.isBlank() && baseUrl != null && !baseUrl.isBlank()
                    && (hasKey() || isLocal());
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** A server on this machine (e.g. Ollama) needs no key. */
        public boolean isLocal() {
            return baseUrl != null && baseUrl.matches("(?i)https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?(/.*)?");
        }

        @Override
        public String toString() {
            return "OpenAi[baseUrl=" + baseUrl + ", model=" + model + ", configured=" + isConfigured() + "]";
        }
    }
}
