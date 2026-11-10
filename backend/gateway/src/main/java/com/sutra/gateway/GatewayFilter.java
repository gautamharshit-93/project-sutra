package com.sutra.gateway;

import com.sutra.common.error.ApiError;
import com.sutra.common.security.JwtCodec;
import com.sutra.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Edge checks before a request is routed: {@code /internal/**} does not exist from outside; a token must be
 * correctly signed, must be a user token (service tokens are for service-to-service calls only), and must still
 * be current for an active user. Requests without a token pass through; the services decide what is public.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayFilter extends OncePerRequestFilter {

    private final JwtCodec jwt;
    private final TokenStateCache tokenStates;
    private final ObjectMapper objectMapper;
    private final PublicEndpointRateLimiter rateLimiter;

    public GatewayFilter(JwtCodec jwt, TokenStateCache tokenStates, ObjectMapper objectMapper,
                         PublicEndpointRateLimiter rateLimiter) {
        this.jwt = jwt;
        this.tokenStates = tokenStates;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/internal")) {
            error(response, path, 404, "Not Found", "NOT_FOUND", "No endpoint at this path");
            return;
        }
        if (rateLimiter.isLimited(path) && !rateLimiter.tryAcquire(PublicEndpointRateLimiter.clientAddress(request), path)) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.secondsToReset()));
            error(response, path, 429, "Too Many Requests", "RATE_LIMITED", "Too many attempts from this computer ("
                    + rateLimiter.perMinute() + " a minute). Try again in " + rateLimiter.secondsToReset() + " seconds.");
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (path.startsWith("/api/") && header != null && header.startsWith("Bearer ")) {
            Optional<JwtCodec.Token> token = jwt.verify(header.substring(7));
            if (token.isEmpty()) {
                error(response, path, 401, "Unauthorized", "UNAUTHENTICATED", "Your session has expired. Sign in again.");
                return;
            }
            if (token.get().isService()) {
                error(response, path, 401, "Unauthorized", "UNAUTHENTICATED", "This token cannot be used here");
                return;
            }
            var principal = token.get().principal();
            TokenStateCache.TokenState state = tokenStates.get(principal.getId());
            if (state == null) {
                error(response, path, 503, "Service Unavailable", "SERVICE_UNAVAILABLE",
                        "The sign-in service is not available right now. Try again in a moment.");
                return;
            }
            if (!state.known() || !state.active() || state.tokenVersion() != principal.getTokenVersion()) {
                error(response, path, 401, "Unauthorized", "SESSION_EXPIRED",
                        "Your account was changed or deactivated. Sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void error(HttpServletResponse response, String path, int status, String error, String code,
                       String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError(Instant.now(), status, error, code, message, path, null, CorrelationIdFilter.current()));
    }
}
