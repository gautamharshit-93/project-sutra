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

    /**
     * The caller's address. Requests relayed by a proxy on this same computer (the Vite dev server, a local reverse
     * proxy) carry the real address in X-Forwarded-For; that header is trusted only from such a local proxy. The proxy
     * appends the address it saw to whatever the client sent, so only the LAST entry is trustworthy: a client cannot
     * escape the limit by sending a made-up X-Forwarded-For of its own.
     */
    public static String clientAddress(jakarta.servlet.http.HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        boolean fromLocalProxy = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
        if (fromLocalProxy && forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return remote;
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
