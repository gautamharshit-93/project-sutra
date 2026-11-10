package com.sutra.extraction.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the procurement settings under {@code sutra.*}: uploaded files, AI extraction and OCR. */
@ConfigurationProperties(prefix = "sutra")
public record AiProperties(Files files, Ai ai) {

    public record Files(long maxSizeBytes) {
    }

    public record Ai(String serviceUrl, int serviceTimeoutSeconds, Groq groq, Ocr ocr,
                     BigDecimal reviewConfidenceThreshold, BigDecimal matchConfidenceThreshold) {

        public Ai {
            ocr = ocr == null ? Ocr.disabled() : ocr;
        }
    }

    /** Groq API settings (OpenAI-compatible). The key comes from the GROQ_API_KEY environment variable and is never logged. */
    public record Groq(String apiKey, String model, String baseUrl, int maxOutputTokens, int timeoutSeconds) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        @Override
        public String toString() {
            return "Groq[model=" + model + ", configured=" + isConfigured() + "]";
        }
    }

    /**
     * OCR for scanned PDFs: Apache PDFBox renders pages at {@code dpi}, the local Tesseract executable reads them.
     * {@code tesseractPath} may be empty: the usual install folders and the PATH are then searched.
     */
    public record Ocr(boolean enabled, String tesseractPath, String languages, int dpi, int maxPages, int timeoutSeconds) {

        public Ocr {
            languages = languages == null || languages.isBlank() ? "eng" : languages.trim();
            dpi = dpi <= 0 ? 300 : dpi;
            maxPages = maxPages <= 0 ? 30 : maxPages;
            timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        }

        public static Ocr disabled() {
            return new Ocr(false, null, "eng", 300, 30, 120);
        }
    }
}
