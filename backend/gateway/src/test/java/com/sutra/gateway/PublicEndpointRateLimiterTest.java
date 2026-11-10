package com.sutra.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Sign-in and vendor sign-up allow 20 requests a minute from one IP address; signed-in work is never limited. */
class PublicEndpointRateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-09T06:00:05Z");

    @Test
    void theTwentyFirstRequestInAMinuteIsRefused() {
        var limiter = new PublicEndpointRateLimiter(20, Clock.fixed(START, ZoneOffset.UTC));

        assertThat(IntStream.range(0, 20).allMatch(i -> limiter.tryAcquire("10.0.0.1", "/api/auth/login"))).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1", "/api/auth/login")).isFalse();
        assertThat(limiter.secondsToReset()).isEqualTo(55);
    }

    @Test
    void eachAddressAndEndpointHasItsOwnAllowance() {
        var limiter = new PublicEndpointRateLimiter(2, Clock.fixed(START, ZoneOffset.UTC));
        limiter.tryAcquire("10.0.0.1", "/api/auth/login");
        limiter.tryAcquire("10.0.0.1", "/api/auth/login");

        assertThat(limiter.tryAcquire("10.0.0.1", "/api/auth/login")).isFalse();
        assertThat(limiter.tryAcquire("10.0.0.2", "/api/auth/login")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1", "/api/auth/register-vendor")).isTrue();
    }

    @Test
    void theAllowanceComesBackNextMinute() {
        var first = new PublicEndpointRateLimiter(1, Clock.fixed(START, ZoneOffset.UTC));
        first.tryAcquire("10.0.0.1", "/api/auth/login");
        assertThat(first.tryAcquire("10.0.0.1", "/api/auth/login")).isFalse();

        // same limiter state, one minute later
        var clock = new MutableClock(START);
        var limiter = new PublicEndpointRateLimiter(1, clock);
        limiter.tryAcquire("10.0.0.1", "/api/auth/login");
        clock.advance(Duration.ofSeconds(60));
        assertThat(limiter.tryAcquire("10.0.0.1", "/api/auth/login")).isTrue();
    }

    @Test
    void onlyThePublicEndpointsAreLimited() {
        var limiter = new PublicEndpointRateLimiter(20, Clock.systemUTC());
        assertThat(limiter.isLimited("/api/auth/login")).isTrue();
        assertThat(limiter.isLimited("/api/auth/register-vendor")).isTrue();
        assertThat(limiter.isLimited("/api/purchase-requests")).isFalse();
        assertThat(limiter.isLimited("/api/auth/me")).isFalse();
    }

    @Test
    void behindTheLocalDevProxyEachUserCountsSeparatelyAndCannotFakeTheirAddress() {
        var viaProxy = new org.springframework.mock.web.MockHttpServletRequest();
        viaProxy.setRemoteAddr("127.0.0.1");
        viaProxy.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(PublicEndpointRateLimiter.clientAddress(viaProxy)).isEqualTo("203.0.113.9");

        var spoofed = new org.springframework.mock.web.MockHttpServletRequest();
        spoofed.setRemoteAddr("127.0.0.1");
        spoofed.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9"); // client-sent value, then the proxy's
        assertThat(PublicEndpointRateLimiter.clientAddress(spoofed)).isEqualTo("203.0.113.9");

        var direct = new org.springframework.mock.web.MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.7");
        direct.addHeader("X-Forwarded-For", "1.2.3.4"); // not from a local proxy: ignored
        assertThat(PublicEndpointRateLimiter.clientAddress(direct)).isEqualTo("198.51.100.7");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
