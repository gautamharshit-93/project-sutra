package com.sutra.extraction.support;

import com.sutra.extraction.config.AiProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared settings for the extraction unit tests.
 */
public final class Fixtures {

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC);

    private static final AtomicLong IDS = new AtomicLong(1000);

    private Fixtures() {
    }

    public static long nextId() {
        return IDS.incrementAndGet();
    }

    public static AiProperties properties() {
        return new AiProperties(
                new AiProperties.Files(1024 * 1024),
                new AiProperties.Ai("http://localhost:8000", 5,
                        new AiProperties.Groq("", "openai/gpt-oss-120b", "http://groq.test", 16000, 30),
                        AiProperties.Ocr.disabled(),
                        new BigDecimal("0.75"), new BigDecimal("0.6")));
    }
}
