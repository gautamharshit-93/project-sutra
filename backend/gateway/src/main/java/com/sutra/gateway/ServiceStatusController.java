package com.sutra.gateway;

import com.sutra.common.error.ApiError;
import com.sutra.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which SUTRA services are up, for the dashboard. Needs a signed-in user (checked by {@link GatewayFilter}). */
@RestController
public class ServiceStatusController {

    public record ServiceStatus(String service, String url, boolean up, long responseMs) {
    }

    private final GatewayProperties properties;
    private final String documentServiceUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ServiceStatusController(GatewayProperties properties,
                                   @Value("${sutra.gateway.document-service-url:http://localhost:8000}") String documentServiceUrl) {
        this.properties = properties;
        this.documentServiceUrl = documentServiceUrl;
    }

    @GetMapping("/api/system/services")
    public ResponseEntity<?> services(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            return ResponseEntity.status(401).body(new ApiError(Instant.now(), 401, "Unauthorized", "UNAUTHENTICATED",
                    "Authentication is required", request.getRequestURI(), null, CorrelationIdFilter.current()));
        }
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("gateway", null);
        properties.routes().forEach(r -> targets.putIfAbsent(r.service(), r.url()));
        targets.put("document-service", documentServiceUrl);

        List<CompletableFuture<ServiceStatus>> checks = new ArrayList<>();
        targets.forEach((name, url) -> checks.add(url == null
                ? CompletableFuture.completedFuture(new ServiceStatus(name, "(this)", true, 0))
                : CompletableFuture.supplyAsync(() -> check(name, url))));
        return ResponseEntity.ok(checks.stream().map(CompletableFuture::join).toList());
    }

    private ServiceStatus check(String name, String url) {
        String health = name.equals("document-service") ? url + "/health" : url + "/actuator/health";
        long start = System.nanoTime();
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(health))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return new ServiceStatus(name, url, response.statusCode() == 200, (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ServiceStatus(name, url, false, (System.nanoTime() - start) / 1_000_000);
        }
    }
}
