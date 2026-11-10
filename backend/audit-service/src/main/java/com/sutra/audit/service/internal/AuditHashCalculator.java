package com.sutra.audit.service.internal;

import com.sutra.audit.entity.AuditLog;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Computes the SHA-256 link of the audit hash chain.
 *
 * <p>The hash covers every field a person could alter (sequence, event id, service, record, RFQ, action, who, when,
 * correlation id, details) plus the previous entry's hash. Two values are normalised first so that what was stored
 * hashes the same when it is read back: the time is cut to microseconds (PostgreSQL's precision) and the JSON
 * details are written in one canonical form (keys sorted, no spaces), because a {@code jsonb} column re-orders keys.
 */
@Component
public class AuditHashCalculator {

    /** The "previous hash" of the first entry. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private static final char SEPARATOR = '\u001F'; // unit separator: cannot occur in the stored text fields

    private final JsonMapper canonicalJson = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public String hash(AuditLog entry, String previousHash) {
        StringBuilder text = new StringBuilder(256);
        append(text, entry.getSequenceNo());
        append(text, entry.getEventId());
        append(text, entry.getService());
        append(text, entry.getEntityType());
        append(text, entry.getEntityId());
        append(text, entry.getPurchaseRequestId());
        append(text, entry.getAction());
        append(text, entry.getPerformedBy());
        append(text, normalise(entry.getPerformedAt()));
        append(text, entry.getCorrelationId());
        append(text, canonical(entry.getDetails()));
        text.append(previousHash);
        return sha256(text.toString());
    }

    /** The precision PostgreSQL keeps, so a re-read time hashes identically. */
    public static Instant normalise(Instant time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MICROS);
    }

    String canonical(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            Object tree = canonicalJson.readValue(json, Object.class);
            return canonicalJson.writeValueAsString(tree);
        } catch (JacksonException e) {
            return json; // not JSON: hash the text as it is
        }
    }

    private static void append(StringBuilder text, Object value) {
        text.append(value == null ? "" : value.toString()).append(SEPARATOR);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
