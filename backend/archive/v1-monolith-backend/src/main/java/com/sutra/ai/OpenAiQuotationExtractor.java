package com.sutra.ai;

import com.sutra.config.ExtractionProperties;
import com.sutra.entity.enums.ExtractionSource;
import com.sutra.exception.QuotationProcessingException;
import java.net.http.HttpClient;
import java.time.Duration;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Quotation extraction through any OpenAI-compatible chat-completions API ({@code POST {base-url}/chat/completions}).
 * The same code works with free providers (Groq, OpenRouter's free models), a local Ollama server, or OpenAI
 * itself: only {@code sutra.extraction.openai.base-url}, {@code model} and the key change.
 *
 * <p>All files (PDFs included) are converted to text by the Python service first, so scanned PDFs without a text
 * layer need the Gemini engine. The model only reads values; the backend recalculates every total.</p>
 */
@Component
public class OpenAiQuotationExtractor implements QuotationExtractor {

    private static final Logger log = LoggerFactory.getLogger(OpenAiQuotationExtractor.class);
    static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);

    private final ExtractionProperties.OpenAi config;
    private final RestClient restClient;
    private final DocumentProcessingClient documentClient;
    private final ObjectMapper objectMapper;
    private final Duration retryDelay;

    @Autowired
    public OpenAiQuotationExtractor(ExtractionProperties properties, RestClient.Builder builder,
                                    DocumentProcessingClient documentClient, ObjectMapper objectMapper) {
        this(properties.openai(), buildClient(properties.openai(), builder), documentClient, objectMapper,
                Duration.ofSeconds(2));
    }

    /** For tests: a RestClient bound to a mock server, and a short retry delay. */
    public OpenAiQuotationExtractor(ExtractionProperties.OpenAi config, RestClient restClient,
                                    DocumentProcessingClient documentClient, ObjectMapper objectMapper,
                                    Duration retryDelay) {
        this.config = config;
        this.restClient = restClient;
        this.documentClient = documentClient;
        this.objectMapper = objectMapper;
        this.retryDelay = retryDelay;
    }

    private static RestClient buildClient(ExtractionProperties.OpenAi config, RestClient.Builder builder) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        String base = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "");
        return builder.clone().baseUrl(base).requestFactory(factory).build();
    }

    @Override
    public ExtractionSource source() {
        return ExtractionSource.OPENAI;
    }

    @Override
    public boolean isAvailable() {
        return config.isConfigured();
    }

    @Override
    public String model() {
        return config.model() == null || config.model().isBlank() ? "not configured" : config.model();
    }

    @Override
    public ExtractedQuotation extract(ExtractionInput input) {
        if (!config.isConfigured()) {
            throw new QuotationProcessingException("The OpenAI-compatible engine is not configured: set OPENAI_API_KEY "
                    + "and OPENAI_MODEL (or OPENAI_BASE_URL for a local Ollama) on the backend and restart it.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", GeminiQuotationExtractor.SYSTEM_PROMPT + "\n" + schemaInstructions()),
                Map.of("role", "user", "content", documentsText(input) + "\n\n"
                        + GeminiQuotationExtractor.instructions(input))));
        body.put("temperature", 0);
        body.put("max_tokens", config.maxOutputTokens());
        body.put("response_format", Map.of("type", "json_object"));

        JsonNode response = sendWithRetry(body);
        String text = response.path("choices").path(0).path("message").path("content").asString("");
        if (text.isBlank()) {
            throw new QuotationProcessingException("The OpenAI-compatible engine returned no extraction result");
        }
        if ("length".equals(response.path("choices").path(0).path("finish_reason").asString(""))) {
            throw new QuotationProcessingException("The OpenAI-compatible engine's answer was cut off because it was "
                    + "too long. Raise OPENAI_MAX_OUTPUT_TOKENS or choose another engine.");
        }
        ExtractedQuotation result;
        try {
            result = objectMapper.readValue(stripCodeFence(text), ExtractedQuotation.class);
        } catch (JacksonException e) {
            throw new QuotationProcessingException("The OpenAI-compatible engine returned data in an unexpected format. "
                    + "Retry, choose another engine, or enter the quotation manually.", e);
        }
        log.info("OpenAI-compatible engine extracted {} line(s) from {} file(s) [{}] (model {})",
                result.items().size(), input.documents().size(), input.fileName(), config.model());
        return result;
    }

    /** The JSON Schema, spelled out: json_object mode is supported more widely than strict json_schema mode. */
    private String schemaInstructions() {
        return "Answer with one JSON object only, no prose, matching this JSON Schema (use null for absent values):\n"
                + objectMapper.writeValueAsString(ExtractionSchema.SCHEMA);
    }

    private String documentsText(ExtractionInput input) {
        StringBuilder text = new StringBuilder();
        int number = 1;
        int chars = 0;
        for (ExtractionInput.Document doc : input.documents()) {
            DocumentProcessingClient.ParsedDocument parsed = documentClient.parse(doc.fileName(), doc.content());
            if (parsed.text() == null || parsed.text().isBlank()) {
                throw new QuotationProcessingException("No readable text was found in " + doc.fileName()
                        + (parsed.likelyScanned() ? " (it looks scanned: use the Gemini engine, which reads images)" : ""));
            }
            chars += parsed.text().length();
            if (chars > GeminiClient.MAX_TEXT_CHARS) {
                throw new QuotationProcessingException("The documents are too large to read in one pass (" + chars
                        + " characters). Split them or enter the data manually.");
            }
            text.append("File ").append(number++).append(" of ").append(input.documents().size()).append(": \"")
                    .append(doc.fileName()).append("\" (").append(doc.format()).append(")\n<document name=\"")
                    .append(doc.fileName()).append("\">\n").append(parsed.text()).append("\n</document>\n\n");
        }
        return text.toString().trim();
    }

    private JsonNode sendWithRetry(Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                RestClient.RequestBodySpec request = restClient.post().uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON);
                if (config.hasKey()) {
                    request = request.header("Authorization", "Bearer " + config.apiKey());
                }
                JsonNode response = request.body(body).retrieve().body(JsonNode.class);
                if (response == null) {
                    throw new QuotationProcessingException("The OpenAI-compatible engine returned an empty response");
                }
                return response;
            } catch (HttpServerErrorException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new QuotationProcessingException("The OpenAI-compatible engine is temporarily unavailable (HTTP "
                            + e.getStatusCode().value() + "). Retry later or choose another engine.", e);
                }
                pause(attempt, e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"));
            } catch (HttpClientErrorException e) {
                int code = e.getStatusCode().value();
                if (code == 429 && attempt < MAX_ATTEMPTS) {
                    pause(attempt, e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"));
                    continue;
                }
                throw clientError(e);
            } catch (ResourceAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new QuotationProcessingException("Could not reach the OpenAI-compatible engine at "
                            + config.baseUrl() + ". Check the address (is Ollama running?) and retry.", e);
                }
                pause(attempt, null);
            } catch (RestClientException e) {
                throw new QuotationProcessingException("OpenAI-compatible request failed: " + e.getClass().getSimpleName(), e);
            }
        }
    }

    private void pause(int attempt, String retryAfter) {
        long millis = retryDelay.toMillis() * (1L << (attempt - 1));
        if (retryAfter != null) {
            try {
                millis = Math.max(millis, (long) (Double.parseDouble(retryAfter.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                // HTTP-date form: keep the computed back-off
            }
        }
        millis = Math.min(millis, MAX_RETRY_WAIT.toMillis());
        log.info("OpenAI-compatible engine busy; retrying in {} ms (attempt {} of {})", millis, attempt + 1, MAX_ATTEMPTS);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuotationProcessingException("Interrupted while waiting to retry the extraction request", e);
        }
    }

    private static QuotationProcessingException clientError(HttpClientErrorException e) {
        int code = e.getStatusCode().value();
        if (code == 429) {
            return new QuotationProcessingException("The OpenAI-compatible engine's rate limit or free quota was reached. "
                    + "Retry later or choose another engine.", e);
        }
        if (code == 401 || code == 403) {
            return new QuotationProcessingException("The OpenAI-compatible engine rejected the API key. Check OPENAI_API_KEY.", e);
        }
        if (code == 404) {
            return new QuotationProcessingException("The OpenAI-compatible engine does not know the model or address. "
                    + "Check OPENAI_MODEL and OPENAI_BASE_URL.", e);
        }
        String detail = e.getResponseBodyAsString();
        detail = detail.length() > 300 ? detail.substring(0, 300) : detail;
        return new QuotationProcessingException("The OpenAI-compatible engine rejected the request (HTTP " + code + "): "
                + detail, e);
    }

    /** Some models wrap JSON in a Markdown code fence even in JSON mode. */
    static String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }
}
