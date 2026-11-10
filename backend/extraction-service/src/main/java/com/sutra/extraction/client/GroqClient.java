package com.sutra.extraction.client;

import com.sutra.extraction.config.ExtractionProperties;
import com.sutra.extraction.config.AiProperties;
import com.sutra.extraction.exception.AiServiceException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
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
 * Thin client for Groq's OpenAI-compatible chat API ({@code POST /chat/completions}), shared by quotation
 * extraction and the assistant. The API key is never logged.
 *
 * <p>Each free-tier Groq model has its own rate limit. When the configured model is still rate-limited after the
 * retries, the request moves on to the next model in {@code sutra.extraction.groq-fallback-models}.</p>
 */
@Component
public class GroqClient {

    /** Above this, document text is not truncated; the caller reports it instead (models have a 131k-token window). */
    public static final int MAX_TEXT_CHARS = 300_000;

    /** Attempts per model, including the first. */
    static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private final AiProperties.Groq config;
    private final RestClient restClient;
    private final Duration retryDelay;
    /** The configured model first, then the free fallback models. */
    private final List<String> models;

    @Autowired
    public GroqClient(AiProperties properties, ExtractionProperties extraction, RestClient.Builder builder) {
        this(properties.ai().groq(), buildClient(properties.ai().groq(), builder), Duration.ofSeconds(2),
                extraction.groqFallbackModels());
    }

    /** For tests: a RestClient bound to a mock server, a short retry delay and optional fallback models. */
    public GroqClient(AiProperties.Groq config, RestClient restClient, Duration retryDelay, List<String> fallbackModels) {
        this.config = config;
        this.restClient = restClient;
        this.retryDelay = retryDelay;
        List<String> all = new ArrayList<>();
        all.add(config.model());
        fallbackModels.stream().filter(m -> !all.contains(m)).forEach(all::add);
        this.models = List.copyOf(all);
    }

    private static RestClient buildClient(AiProperties.Groq config, RestClient.Builder builder) {
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

    /** One chat message. */
    public static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * A strict JSON Schema response format: the model must return JSON matching {@code schema}. Every object in the
     * schema needs all properties required and {@code additionalProperties: false}.
     */
    public static Map<String, Object> jsonSchema(String name, Map<String, Object> schema) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", name);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", schema);
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", jsonSchema);
        return format;
    }

    /** The answer of one chat completion. */
    public record Completion(String content, String model, long inputTokens, long outputTokens) {
    }

    /**
     * Sends one chat completion, trying {@code preferredModel} first when it is one of the configured models; the
     * others follow in their usual order if it is rate-limited. {@code responseFormat} may be null for free text.
     */
    public Completion complete(List<Map<String, Object>> messages, Map<String, Object> responseFormat,
                               int maxOutputTokens, String preferredModel) {
        if (!config.isConfigured()) {
            throw new AiServiceException("Groq is not configured: set the GROQ_API_KEY environment variable "
                    + "on the backend and restart it.");
        }
        List<String> order = this.models;
        if (preferredModel != null && order.contains(preferredModel)) {
            List<String> ordered = new ArrayList<>();
            ordered.add(preferredModel);
            order.stream().filter(m -> !m.equals(preferredModel)).forEach(ordered::add);
            order = ordered;
        }
        for (int i = 0; i < order.size(); i++) {
            String model = order.get(i);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            if (responseFormat != null) {
                body.put("response_format", responseFormat);
            }
            body.put("temperature", 0);
            body.put("max_completion_tokens", maxOutputTokens);
            if (model.startsWith("openai/gpt-oss")) {
                body.put("reasoning_effort", "low"); // reasoning tokens count against max_completion_tokens
            }
            try {
                return read(sendWithRetry(body), model);
            } catch (AiServiceException e) {
                if (!isRateLimit(e) || i == order.size() - 1) {
                    throw order.size() > 1 && isRateLimit(e)
                            ? new AiServiceException("All free Groq models are rate-limited (" + String.join(", ", order)
                                    + "). Retry in a minute or choose another engine.", e)
                            : e;
                }
                log.info("Groq model {} is rate-limited; switching to {}", model, order.get(i + 1));
            }
        }
        throw new AiServiceException("Groq returned no answer");
    }

    private static Completion read(JsonNode response, String model) {
        if (response == null) {
            throw new AiServiceException("Groq returned an empty response");
        }
        JsonNode choice = response.path("choices").path(0);
        String finish = choice.path("finish_reason").asString("");
        if ("length".equals(finish)) {
            throw new AiServiceException("Groq's answer was cut off because it was too long.");
        }
        String content = choice.path("message").path("content").asString("");
        JsonNode usage = response.path("usage");
        return new Completion(content, response.path("model").asString(model),
                usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
    }

    /**
     * Sends the request, retrying temporary failures (HTTP 500/502/503/504, short 429 rate limits and dropped
     * connections) up to {@link #MAX_ATTEMPTS} times with back-off, honouring a Retry-After header when present.
     */
    private JsonNode sendWithRetry(Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post().uri("/chat/completions")
                        .header("Authorization", "Bearer " + config.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpServerErrorException e) {
                if (attempt >= MAX_ATTEMPTS || e.getStatusCode().value() == 501) {
                    throw new AiServiceException("Groq is temporarily unavailable (HTTP " + e.getStatusCode().value()
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
                    throw new AiServiceException("Could not reach the Groq API. Check the internet connection and retry.", e);
                }
                pause(attempt, null);
            } catch (RestClientException e) {
                throw new AiServiceException("Groq request failed: " + e.getClass().getSimpleName(), e);
            }
        }
    }

    /** True when Groq refused the request because a rate limit or quota was reached (HTTP 429). */
    static boolean isRateLimit(AiServiceException e) {
        return e.getCause() instanceof HttpClientErrorException h && h.getStatusCode().value() == 429;
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
        log.info("Groq temporarily unavailable; retrying in {} ms (attempt {} of {})", millis, attempt + 1, MAX_ATTEMPTS);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException("Interrupted while waiting to retry the Groq request", e);
        }
    }

    private static AiServiceException clientError(HttpClientErrorException e) {
        int code = e.getStatusCode().value();
        if (code == 429) {
            return new AiServiceException("Groq free-tier limit reached (requests or tokens per minute). Retry later.", e);
        }
        if (code == 401 || code == 403) {
            return new AiServiceException("Groq rejected the API key. Check GROQ_API_KEY.", e);
        }
        if (code == 413) {
            return new AiServiceException("The documents are too large for one Groq request on the free tier. "
                    + "Split them or enter the quotation manually.", e);
        }
        String detail = e.getResponseBodyAsString();
        detail = detail.length() > 300 ? detail.substring(0, 300) : detail;
        return new AiServiceException("Groq rejected the request (HTTP " + code + "): " + detail, e);
    }
}
