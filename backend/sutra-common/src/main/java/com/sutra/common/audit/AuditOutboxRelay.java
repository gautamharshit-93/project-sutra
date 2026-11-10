package com.sutra.common.audit;

import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceUrls;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Delivers outbox events to the audit service, oldest first, and deletes them once accepted. Each batch is one
 * request, so a failure stops the batch as a whole and nothing is delivered out of order. While the audit service
 * is down the events wait in the outbox and the relay backs off exponentially (2 s, 4 s ... up to 60 s) instead of
 * retrying every second. The audit service ignores events it already has, so a repeated delivery is harmless.
 */
@Component
@ConditionalOnProperty(name = "sutra.audit.outbox-relay", havingValue = "true", matchIfMissing = true)
public class AuditOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);

    private final AuditOutboxRepository outbox;
    private final TransactionTemplate tx;
    private final RestClient auditClient;
    private final String serviceName;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private volatile boolean warned;
    private int failures;
    private Instant nextAttemptAt = Instant.EPOCH;

    public AuditOutboxRelay(AuditOutboxRepository outbox, TransactionTemplate tx, InternalRestClients clients,
                            ServiceUrls urls, @Value("${spring.application.name}") String serviceName) {
        this.outbox = outbox;
        this.tx = tx;
        this.auditClient = clients.forBaseUrl(urls.audit());
        this.serviceName = serviceName;
    }

    @Scheduled(fixedDelayString = "${sutra.audit.relay-interval-ms:1000}")
    public void deliver() {
        if (Instant.now().isBefore(nextAttemptAt)) {
            return; // backing off after a failed delivery
        }
        List<AuditOutboxEvent> batch = outbox.findTop100ByOrderByIdAsc();
        if (batch.isEmpty()) {
            return;
        }
        try {
            auditClient.post().uri("/internal/audit-events")
                    .body(batch.stream().map(e -> e.toMessage(serviceName)).toList())
                    .retrieve().toBodilessEntity();
            tx.executeWithoutResult(s -> outbox.deleteAllByIdInBatch(batch.stream().map(AuditOutboxEvent::getId).toList()));
            if (warned) {
                log.info("Audit service reachable again; delivering queued audit events");
                warned = false;
            }
            failures = 0;
            nextAttemptAt = Instant.EPOCH;
        } catch (RestClientException e) {
            failures++;
            Duration wait = backoff(failures);
            nextAttemptAt = Instant.now().plus(wait);
            if (!warned) {
                log.warn("Audit service unreachable ({} event(s) queued), retrying with back-off: {}", batch.size(),
                        e.getMessage());
                warned = true;
            }
        }
    }

    /** 2 s after the first failure, doubling each time, never more than a minute. */
    static Duration backoff(int failures) {
        long seconds = 1L << Math.min(failures, 6);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }
}
