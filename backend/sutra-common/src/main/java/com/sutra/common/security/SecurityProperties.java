package com.sutra.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sutra.security.*}. Every service shares the JWT secret: the auth service signs user tokens, each service
 * verifies them itself and signs its own short-lived service tokens. {@code public-paths} need no token.
 */
@ConfigurationProperties(prefix = "sutra.security")
public record SecurityProperties(String jwtSecret, long jwtExpirationMinutes, List<String> publicPaths) {

    public SecurityProperties {
        jwtExpirationMinutes = jwtExpirationMinutes <= 0 ? 480 : jwtExpirationMinutes;
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }

    @Override
    public String toString() {
        return "SecurityProperties[jwtExpirationMinutes=" + jwtExpirationMinutes + ", publicPaths=" + publicPaths + "]";
    }
}
