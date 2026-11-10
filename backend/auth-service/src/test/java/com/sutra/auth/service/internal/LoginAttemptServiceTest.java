package com.sutra.auth.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sutra.auth.exception.AccountLockedException;
import com.sutra.auth.support.Fixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T06:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MutableClock clock;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new LoginAttemptService(Fixtures.properties(), clock); // 5 failures, 15 minutes
    }

    @Test
    void locksAfterFiveConsecutiveFailuresAndUnlocksAfterTheLockoutWindow() {
        for (int i = 1; i <= 4; i++) {
            assertThat(service.recordFailure("Asha.Buyer")).isFalse();
        }
        assertThat(service.recordFailure("asha.buyer")).as("usernames are case-insensitive").isTrue();
        assertThatThrownBy(() -> service.requireNotLocked("asha.buyer"))
                .isInstanceOf(AccountLockedException.class).hasMessageContaining("15 minute");

        clock.advance(Duration.ofMinutes(15));
        assertThatCode(() -> service.requireNotLocked("asha.buyer")).doesNotThrowAnyException();
        assertThat(service.recordFailure("asha.buyer")).as("counter restarts after a lockout").isFalse();
    }

    @Test
    void successfulLoginResetsTheCounter() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("ravi");
        }
        service.recordSuccess("ravi");
        assertThat(service.recordFailure("ravi")).isFalse();
    }

    @Test
    void oldFailuresAreForgotten() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("vic");
        }
        clock.advance(Duration.ofMinutes(16));
        assertThat(service.recordFailure("vic")).isFalse();
    }

    @Test
    void otherUsersAreNotAffected() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("attacked");
        }
        assertThatCode(() -> service.requireNotLocked("someone.else")).doesNotThrowAnyException();
    }
}
