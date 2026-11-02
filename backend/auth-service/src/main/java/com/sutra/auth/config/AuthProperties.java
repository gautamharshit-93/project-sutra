package com.sutra.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sutra.auth.*}: the first administrator (created on an empty database) and the sign-in lockout.
 * Sign-in is locked for {@code lockoutMinutes} after {@code maxFailedLogins} consecutive failures.
 */
@ConfigurationProperties(prefix = "sutra.auth")
public record AuthProperties(BootstrapAdmin bootstrapAdmin, int maxFailedLogins, int lockoutMinutes) {

    public record BootstrapAdmin(String username, String password, String email) {

        @Override
        public String toString() {
            return "BootstrapAdmin[username=" + username + ", email=" + email + "]";
        }
    }
}
