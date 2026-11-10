package com.sutra.gateway;

import com.sutra.common.error.ApiError;
import com.sutra.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Forwards every {@code /api/**} request to the service that owns it and streams the answer back unchanged.
 * Request and response bodies are streamed, so large uploads and downloads are never held in memory here.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Hop-by-hop headers, and those the HTTP client sets itself. */
    private static final Set<String> SKIP_REQUEST = Set.of("connection", "keep-alive", "transfer-encoding", "te",
            "trailer", "upgrade", "host", "content-length", "expect", "proxy-connection", "origin");
    private static final Set<String> SKIP_RESPONSE = Set.of("connection", "keep-alive", "transfer-encoding", "te",
            "trailer", "upgrade", "content-length");
    /** Each service answers CORS through the gateway's own handling; their headers would be duplicates. */
    private static final String ACCESS_CONTROL = "access-control-";

    private final RouteTable routes;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public ProxyController(RouteTable routes, GatewayProperties properties, ObjectMapper objectMapper) {
        this.routes = routes;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @RequestMapping("/api/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        var route = routes.find(path);
        if (route.isEmpty()) {
            error(response, path, 404, "Not Found", "NOT_FOUND", "No endpoint at this path");
            return;
        }
        String query = request.getQueryString();
        URI target = URI.create(route.get().url() + path + (query == null ? "" : "?" + query));

        HttpRequest.Builder out = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()));
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!SKIP_REQUEST.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : Collections.list(request.getHeaders(name))) {
                    out.header(name, value);
                }
            }
        }
        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            out.setHeader(CorrelationIdFilter.HEADER, correlationId);
        }
        out.header("X-Forwarded-For", request.getRemoteAddr());
        boolean hasBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
        out.method(request.getMethod(), hasBody
                ? HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return request.getInputStream();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                : HttpRequest.BodyPublishers.noBody());

        HttpResponse<InputStream> answer;
        try {
            answer = http.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error(response, path, 503, "Service Unavailable", "SERVICE_UNAVAILABLE", "The request was interrupted");
            return;
        } catch (IOException e) {
            log.warn("{} unreachable for {} {}: {}", route.get().service(), request.getMethod(), path, e.toString());
            error(response, path, 503, "Service Unavailable", "SERVICE_UNAVAILABLE",
                    "The " + route.get().service() + " is not available right now. Try again in a moment.");
            return;
        }

        response.setStatus(answer.statusCode());
        answer.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!SKIP_RESPONSE.contains(lower) && !lower.startsWith(ACCESS_CONTROL) && !lower.equals(":status")
                    && !lower.equalsIgnoreCase(CorrelationIdFilter.HEADER)) {
                values.forEach(v -> response.addHeader(name, v));
            }
        });
        answer.headers().firstValueAsLong("content-length").ifPresent(response::setContentLengthLong);
        try (InputStream body = answer.body(); OutputStream sink = response.getOutputStream()) {
            body.transferTo(sink);
        }
    }

    private void error(HttpServletResponse response, String path, int status, String error, String code,
                       String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError(Instant.now(), status, error, code, message, path, null, CorrelationIdFilter.current()));
    }
}
