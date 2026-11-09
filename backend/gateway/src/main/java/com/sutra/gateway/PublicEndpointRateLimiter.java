package com.sutra.gateway;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits the two endpoints anyone can call without signing in - sign-in and vendor sign-up - to a number of
 * requests per minute from one IP address (20 by default). It slows down password guessing and sign-up spam;
 * signed-in work is not limited. Fixed one-minute windows, kept in memory (one gateway).
 */
@Component
public class PublicEndpointRateLimiter {

    static final Set<String> LIMITED_PATHS = Set.of("/api/auth/login", "/api/auth/register-vendor");

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long minute, int count) {
    }

    public PublicEndpointRateLimiter(@Value("${sutra.gateway.public-rate-limit-per-minute:20}") int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    public boolean isLimited(String path) {
        return LIMITED_PATHS.contains(path);
    }

    /** Counts the request; false when this IP has used up its requests for this endpoint in the current minute. */
    public boolean tryAcquire(String clientIp, String path) {
        long minute = clock.millis() / 60_000;
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute); // drop old windows
        }
        Window w = windows.merge(clientIp + " " + path, new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
        return w.count() <= perMinute;
    }

    /** Seconds until the current window ends (for the Retry-After header). */
    public long secondsToReset() {
        return 60 - (clock.millis() / 1000) % 60;
    }

    public int perMinute() {
        return perMinute;
    }
}
