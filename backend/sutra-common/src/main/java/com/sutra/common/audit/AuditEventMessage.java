package com.sutra.common.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit event as sent from a service's outbox to the audit service. {@code eventId} makes delivery idempotent;
 * {@code purchaseRequestId} groups everything that happened to one RFQ (its quotations, comparisons, award).
 * {@code correlationId} is the X-Correlation-Id of the request that caused the event.
 * {@code details} is a JSON object and never contains file content or credentials.
 */
public record AuditEventMessage(UUID eventId, String service, String entityType, Long entityId,
                                Long purchaseRequestId, String action, String performedBy, Instant performedAt,
                                String details, String correlationId) {
}
