package com.sutra.gateway;

import com.sutra.common.security.JwtCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks the auth service whether a user is still active and which token version is current, caching the answer for
 * a few seconds. A deactivated user, or one whose roles or password changed, is therefore signed out everywhere
 * within that time instead of when the token expires.
 */
@Component
public class TokenStateCache {

    private static final Logger log = LoggerFactory.getLogger(TokenStateCache.class);

    /** What auth said about a user. {@code known} is false when the user no longer exists. */
    public record TokenState(boolean known, boolean active, long tokenVersion) {
    }

    private record Entry(TokenState state, Instant expires) {
    }

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JwtCodec jwt;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;

    public TokenStateCache(JwtCodec jwt, ObjectMapper objectMapper, GatewayProperties properties) {
        this.jwt = jwt;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** The user's state, or null when the auth service cannot be reached (the caller then refuses the request). */
    public TokenState get(Long userId) {
        Entry cached = cache.get(userId);
        if (cached != null && Instant.now().isBefore(cached.expires())) {
            return cached.state();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.authUrl() + "/internal/users/" + userId + "/token-state"))
                    .header("Authorization", "Bearer " + jwt.issueService("gateway"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            TokenState state;
            if (response.statusCode() == 404) {
                state = new TokenState(false, false, -1);
            } else if (response.statusCode() == 200) {
                JsonNode body = objectMapper.readTree(response.body());
                state = new TokenState(true, body.path("active").asBoolean(false), body.path("tokenVersion").asLong(-1));
            } else {
                log.warn("Auth service answered {} to a token-state check", response.statusCode());
                return null;
            }
            cache.put(userId, new Entry(state, Instant.now().plusSeconds(properties.tokenCheckCacheSeconds())));
            return state;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Auth service unreachable for a token-state check: {}", e.getMessage());
            return null;
        }
    }
}
