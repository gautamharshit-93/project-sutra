package com.sutra.procurement.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the procurement settings under {@code sutra.*}: uploaded files and AI extraction. */
@ConfigurationProperties(prefix = "sutra")
public record SutraProperties(Files files, Ai ai) {

    public record Files(long maxSizeBytes) {
    }

    public record Ai(String serviceUrl, int serviceTimeoutSeconds, Gemini gemini,
                     BigDecimal reviewConfidenceThreshold, BigDecimal matchConfidenceThreshold) {
    }

    /** Gemini API settings. The key comes from the GEMINI_API_KEY environment variable and is never logged. */
    public record Gemini(String apiKey, String model, String baseUrl, int maxOutputTokens, int timeoutSeconds) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Gemini[model=" + model + ", configured=" + isConfigured() + "]";
        }
    }
}
