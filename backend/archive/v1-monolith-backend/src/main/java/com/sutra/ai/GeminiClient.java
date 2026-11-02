package com.sutra.ai;

import com.sutra.config.ExtractionProperties;
import com.sutra.config.SutraProperties;
import com.sutra.entity.enums.FileFormat;
import com.sutra.exception.AiServiceException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Thin client for the Gemini Interactions API ({@code POST /v1beta/interactions}), shared by quotation extraction
 * and the AI assistant. Every request is sent with {@code store: false}. The API key is never logged.
 *
 * <p>Each free-tier Gemini model has its own rate limit. When the configured model is still rate-limited after
 * the retries, the request moves on to the next model in {@code sutra.extraction.gemini-fallback-models}, so the
 * free tier keeps working without paying.</p>
 */
@Component
public class GeminiClient {

    /** Above this, document text is not truncated; the caller reports it instead. */
    public static final int MAX_TEXT_CHARS = 600_000;
    /** Gemini accepts PDFs up to 50 MB; base64 adds about a third, so the total stays well below. */
    public static final long MAX_PDF_BYTES = 35L * 1024 * 1024;

    /** Attempts per request, including the first. */
    static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final SutraProperties.Gemini config;
    private final RestClient restClient;
    private final Duration retryDelay;
    /** The configured model first, then the free fallback models. */
    private final List<String> models;

    @Autowired
    public GeminiClient(SutraProperties properties, ExtractionProperties extraction, RestClient.Builder builder) {
        this(properties.ai().gemini(), buildClient(properties.ai().gemini(), builder), Duration.ofSeconds(2),
                extraction.geminiFallbackModels());
    }

    /** For tests: a RestClient bound to a mock server, and a short retry delay. */
    public GeminiClient(SutraProperties.Gemini config, RestClient restClient, Duration retryDelay) {
        this(config, restClient, retryDelay, List.of());
    }

    /** For tests: as above, with fallback models. */
    public GeminiClient(SutraProperties.Gemini config, RestClient restClient, Duration retryDelay,
                        List<String> fallbackModels) {
        this.config = config;
        this.restClient = restClient;
        this.retryDelay = retryDelay;
        List<String> all = new ArrayList<>();
        all.add(config.model());
        fallbackModels.stream().filter(m -> !all.contains(m)).forEach(all::add);
        this.models = List.copyOf(all);
    }

    private static RestClient buildClient(SutraProperties.Gemini config, RestClient.Builder builder) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        return builder.clone().baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    public String model() {
        return config.model();
    }

    /** The configured model followed by the fallback models, in the order they are tried. */
    public List<String> models() {
        return models;
    }

    public int maxOutputTokens() {
        return config.maxOutputTokens();
    }

    /**
     * Sends one interaction. {@code responseFormat} may be null for free-text answers.
     * Returns the response after checking its status; use {@link #outputText} to read the answer.
     */
    public JsonNode interact(List<Map<String, Object>> input, Map<String, Object> responseFormat, int maxOutputTokens) {
        if (!config.isConfigured()) {
            throw new AiServiceException("Gemini is not configured: set the GEMINI_API_KEY environment variable "
                    + "on the backend and restart it.");
        }
        JsonNode response = null;
        for (int i = 0; i < models.size(); i++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", models.get(i));
            body.put("input", input);
            if (responseFormat != null) {
                body.put("response_format", responseFormat);
            }
            body.put("generation_config", Map.of("max_output_tokens", maxOutputTokens));
            body.put("store", false);
            try {
                response = sendWithRetry(body);
                break;
            } catch (AiServiceException e) {
                if (!isRateLimit(e) || i == models.size() - 1) {
                    throw models.size() > 1 && isRateLimit(e)
                            ? new AiServiceException("All free Gemini models are rate-limited (" + String.join(", ", models)
                                    + "). Retry later or choose another engine.", e)
                            : e;
                }
                log.info("Gemini model {} is rate-limited; switching to {}", models.get(i), models.get(i + 1));
            }
        }
        if (response == null) {
            throw new AiServiceException("Gemini returned an empty response");
        }
        String status = response.path("status").asString("");
        if ("incomplete".equals(status)) {
            throw new AiServiceException("Gemini's answer was cut off because it was too long.");
        }
        if ("failed".equals(status) || "cancelled".equals(status)) {
            String reason = response.path("errors").path(0).path("message").asString("no details");
            throw new AiServiceException("Gemini could not complete the request: " + reason);
        }
        return response;
    }

    /**
     * Sends the request, retrying temporary failures (HTTP 500/502/503/504, short 429 rate limits and dropped
     * connections) up to {@link #MAX_ATTEMPTS} times with back-off, honouring a Retry-After header when present.
     */
    private JsonNode sendWithRetry(Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post().uri("/v1beta/interactions")
                        .header("x-goog-api-key", config.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpServerErrorException e) {
                if (attempt >= MAX_ATTEMPTS || e.getStatusCode().value() == 501) {
                    throw new AiServiceException("Gemini is temporarily unavailable (HTTP " + e.getStatusCode().value()
                            + ") after " + attempt + " attempt(s). Retry in a few minutes.", e);
                }
                pause(attempt, e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"));
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_ATTEMPTS) {
                    pause(attempt, e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"));
                    continue;
                }
                throw clientError(e);
            } catch (ResourceAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new AiServiceException("Could not reach the Gemini API. Check the internet connection and retry.", e);
                }
                pause(attempt, null);
            } catch (RestClientException e) {
                throw new AiServiceException("Gemini request failed: " + e.getClass().getSimpleName(), e);
            }
        }
    }

    /** True when Gemini refused the request because a rate limit or quota was reached (HTTP 429). */
    static boolean isRateLimit(AiServiceException e) {
        return e.getCause() instanceof HttpClientErrorException h && h.getStatusCode().value() == 429;
    }

    private void pause(int attempt, String retryAfter) {
        long millis = retryDelay.toMillis() * (1L << (attempt - 1)); // e.g. 2s, 4s
        if (retryAfter != null) {
            try {
                millis = Math.max(millis, Long.parseLong(retryAfter.trim()) * 1000);
            } catch (NumberFormatException ignored) {
                // HTTP-date form: keep the computed back-off
            }
        }
        millis = Math.min(millis, MAX_RETRY_WAIT.toMillis());
        log.info("Gemini temporarily unavailable; retrying in {} ms (attempt {} of {})", millis, attempt + 1, MAX_ATTEMPTS);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Interrupted while waiting to retry the Gemini request", e);
        }
    }

    /** Concatenated text of all {@code model_output} steps (thought steps are skipped). */
    public static String outputText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asString(""))) {
                continue;
            }
            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").asString(""))) {
                    text.append(content.path("text").asString(""));
                }
            }
        }
        return text.toString();
    }

    public static long inputTokens(JsonNode response) {
        return response.path("usage").path("total_input_tokens").asLong(0);
    }

    public static long outputTokens(JsonNode response) {
        return response.path("usage").path("total_output_tokens").asLong(0);
    }

    /**
     * Input parts for a set of original files: PDFs are sent as documents (Gemini reads them visually, including
     * scans); Excel and Word files are converted to text by the Python service first. Each file is labelled.
     */
    public List<Map<String, Object>> documentParts(List<ExtractionInput.Document> documents,
                                                   DocumentProcessingClient documentClient) {
        List<Map<String, Object>> parts = new ArrayList<>();
        long pdfBytes = 0;
        int textChars = 0;
        int number = 1;
        for (ExtractionInput.Document doc : documents) {
            String label = "File " + number++ + " of " + documents.size() + ": \"" + doc.fileName() + "\" ("
                    + doc.format() + ")";
            if (doc.format() == FileFormat.PDF) {
                pdfBytes += doc.content().length;
                if (pdfBytes > MAX_PDF_BYTES) {
                    throw new AiServiceException("The PDF files are too large to send together ("
                            + pdfBytes / (1024 * 1024) + " MB). Remove or compress some files.");
                }
                parts.add(text(label));
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("type", "document");
                document.put("data", Base64.getEncoder().encodeToString(doc.content()));
                document.put("mime_type", "application/pdf");
                parts.add(document);
            } else {
                DocumentProcessingClient.ParsedDocument parsed = documentClient.parse(doc.fileName(), doc.content());
                if (parsed.text() == null || parsed.text().isBlank()) {
                    throw new AiServiceException("No readable text was found in " + doc.fileName());
                }
                textChars += parsed.text().length();
                if (textChars > MAX_TEXT_CHARS) {
                    throw new AiServiceException("The documents are too large to read in one pass (" + textChars
                            + " characters). Split them or enter the data manually.");
                }
                parts.add(text(label + "\n<document name=\"" + doc.fileName() + "\">\n" + parsed.text() + "\n</document>"));
            }
        }
        return parts;
    }

    public static Map<String, Object> text(String value) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "text");
        part.put("text", value);
        return part;
    }

    private static AiServiceException clientError(HttpClientErrorException e) {
        int code = e.getStatusCode().value();
        if (code == 429) {
            return new AiServiceException("Gemini free-tier limit reached (requests per minute or per day). "
                    + "Retry later.", e);
        }
        if (code == 401 || code == 403) {
            return new AiServiceException("Gemini rejected the API key. Check GEMINI_API_KEY.", e);
        }
        String detail = e.getResponseBodyAsString();
        detail = detail.length() > 300 ? detail.substring(0, 300) : detail;
        return new AiServiceException("Gemini rejected the request (HTTP " + code + "): " + detail, e);
    }
}
