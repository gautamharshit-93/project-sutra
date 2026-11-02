package com.sutra.config;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code sutra.*} block in application.yml. */
@ConfigurationProperties(prefix = "sutra")
public record SutraProperties(Security security, BootstrapAdmin bootstrapAdmin, Files files, Ai ai) {

    public record Security(String jwtSecret, long jwtExpirationMinutes, List<String> corsAllowedOrigins,
                           int maxFailedLogins, int lockoutMinutes) {
    }

    public record BootstrapAdmin(String username, String password, String email) {
    }

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
