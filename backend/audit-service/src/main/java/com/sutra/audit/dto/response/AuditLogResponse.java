package com.sutra.audit.dto.response;

import com.sutra.audit.entity.AuditLog;

import java.time.Instant;

/** One audit entry as shown to users. {@code details} is a JSON object. */
public record AuditLogResponse(Long id, Long sequenceNo, String service, String entityType, Long entityId,
                               Long purchaseRequestId, String action, String performedBy, Instant performedAt,
                               String correlationId, String details, String hash) {

    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(a.getId(), a.getSequenceNo(), a.getService(), a.getEntityType(), a.getEntityId(),
                a.getPurchaseRequestId(), a.getAction(), a.getPerformedBy(), a.getPerformedAt(), a.getCorrelationId(),
                a.getDetails(), a.getHash());
    }
}
