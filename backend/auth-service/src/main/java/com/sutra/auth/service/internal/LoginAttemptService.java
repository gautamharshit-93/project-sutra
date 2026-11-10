package com.sutra.auth.service.internal;

import com.sutra.auth.config.AuthProperties;
import com.sutra.auth.exception.AccountLockedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Brute-force protection for the login endpoint: after {@code maxFailedLogins} consecutive failures a username is
 * locked for {@code lockoutMinutes}. Failures older than the lockout window are forgotten. State is in memory, so a
 * restart clears it; that is acceptable for a single backend instance.
 */
@Component
public class LoginAttemptService {

    private record Attempts(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lockout;
    private final Clock clock;

    public LoginAttemptService(AuthProperties properties, Clock clock) {
        this.maxFailures = Math.max(1, properties.maxFailedLogins());
        this.lockout = Duration.ofMinutes(Math.max(1, properties.lockoutMinutes()));
        this.clock = clock;
    }

    /** Throws {@link AccountLockedException} while the username is locked. */
    public void requireNotLocked(String username) {
        Attempts a = attempts.get(key(username));
        Instant now = clock.instant();
        if (a != null && a.lockedUntil() != null && now.isBefore(a.lockedUntil())) {
            long minutes = Math.max(1, (Duration.between(now, a.lockedUntil()).toSeconds() + 59) / 60);
            throw new AccountLockedException("Too many failed sign-in attempts. Try again in " + minutes
                    + " minute(s) or ask an administrator.");
        }
    }

    /** Records a failure; returns true when this failure locked the account. */
    public boolean recordFailure(String username) {
        Instant now = clock.instant();
        Attempts updated = attempts.compute(key(username), (k, a) -> {
            int previous = a == null || a.lastFailure().plus(lockout).isBefore(now)
                    || (a.lockedUntil() != null && !now.isBefore(a.lockedUntil())) ? 0 : a.failures();
            int failures = previous + 1;
            return new Attempts(failures, now, failures >= maxFailures ? now.plus(lockout) : null);
        });
        return updated.lockedUntil() != null;
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    /** Lets an administrator unlock a user early (e.g. after a password reset). */
    public void unlock(String username) {
        attempts.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
