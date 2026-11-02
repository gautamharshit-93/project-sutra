package com.sutra.common.audit;

import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceUrls;
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
 * Delivers outbox events to the audit service every second, oldest first, and deletes them once accepted. If the
 * audit service is down the events wait in the outbox; the audit service ignores events it already has, so a
 * repeated delivery is harmless.
 */
@Component
@ConditionalOnProperty(name = "sutra.audit.outbox-relay", havingValue = "true", matchIfMissing = true)
public class AuditOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);

    private final AuditOutboxRepository outbox;
    private final TransactionTemplate tx;
    private final RestClient auditClient;
    private final String serviceName;
    private volatile boolean warned;

    public AuditOutboxRelay(AuditOutboxRepository outbox, TransactionTemplate tx, InternalRestClients clients,
                            ServiceUrls urls, @Value("${spring.application.name}") String serviceName) {
        this.outbox = outbox;
        this.tx = tx;
        this.auditClient = clients.forBaseUrl(urls.audit());
        this.serviceName = serviceName;
    }

    @Scheduled(fixedDelayString = "${sutra.audit.relay-interval-ms:1000}")
    public void deliver() {
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
        } catch (RestClientException e) {
            if (!warned) {
                log.warn("Audit service unreachable ({} event(s) queued): {}", batch.size(), e.getMessage());
                warned = true;
            }
        }
    }
}
