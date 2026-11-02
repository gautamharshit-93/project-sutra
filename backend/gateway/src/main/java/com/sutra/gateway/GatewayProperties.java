package com.sutra.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sutra.gateway.*}. Routes are tried in order; the first whose path pattern matches wins, so specific
 * patterns (e.g. an RFQ's history, served by the audit service) come before general ones.
 */
@ConfigurationProperties(prefix = "sutra.gateway")
public record GatewayProperties(List<Route> routes, List<String> corsAllowedOrigins, String authUrl,
                                int tokenCheckCacheSeconds, int requestTimeoutSeconds) {

    public GatewayProperties {
        routes = routes == null ? List.of() : List.copyOf(routes);
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        tokenCheckCacheSeconds = tokenCheckCacheSeconds <= 0 ? 15 : tokenCheckCacheSeconds;
        requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 600 : requestTimeoutSeconds;
    }

    /** {@code service} is a name for logs and errors; {@code paths} are Ant patterns such as {@code /api/users/**}. */
    public record Route(String service, String url, List<String> paths) {
    }
}
