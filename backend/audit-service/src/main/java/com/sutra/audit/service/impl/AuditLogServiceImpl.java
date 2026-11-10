package com.sutra.audit.service;

import com.sutra.audit.entity.AuditLog;
import com.sutra.audit.repository.AuditLogRepository;
import com.sutra.common.audit.AuditEventMessage;
import com.sutra.common.web.PageResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores audit events sent by the services and answers audit-trail queries. Entries are never changed. */
@Service
public class AuditLogService {

    /** One audit entry as shown to users. {@code details} is a JSON object. */
    public record AuditLogResponse(Long id, String service, String entityType, Long entityId, Long purchaseRequestId,
                                   String action, String performedBy, Instant performedAt, String details) {
        static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(a.getId(), a.getService(), a.getEntityType(), a.getEntityId(),
                    a.getPurchaseRequestId(), a.getAction(), a.getPerformedBy(), a.getPerformedAt(), a.getDetails());
        }
    }

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Stores a batch from one service's outbox. Events already stored (a repeated delivery) are skipped, so the
     * call is idempotent. Returns how many were new.
     */
    @Transactional
    public int ingest(List<AuditEventMessage> events) {
        List<AuditEventMessage> valid = events.stream().filter(Objects::nonNull)
                .filter(e -> e.eventId() != null && e.entityType() != null && e.action() != null)
                .toList();
        if (valid.isEmpty()) {
            return 0;
        }
        Set<UUID> seen = new HashSet<>(repository.findExistingEventIds(valid.stream().map(AuditEventMessage::eventId).toList()));
        int stored = 0;
        for (AuditEventMessage e : valid) {
            if (!seen.add(e.eventId())) {
                continue;
            }
            AuditLog log = new AuditLog();
            log.setEventId(e.eventId());
            log.setService(truncate(e.service() == null ? "unknown" : e.service(), 40));
            log.setEntityType(truncate(e.entityType(), 50));
            log.setEntityId(e.entityId());
            log.setPurchaseRequestId(e.purchaseRequestId());
            log.setAction(truncate(e.action(), 50));
            log.setPerformedBy(truncate(e.performedBy() == null ? "system" : e.performedBy(), 60));
            log.setPerformedAt(e.performedAt() == null ? Instant.now() : e.performedAt());
            log.setDetails(e.details());
            repository.save(log);
            stored++;
        }
        return stored;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String entityType, Long entityId, String performedBy, int page, int size) {
        return PageResponse.of(repository.search(blankToNull(entityType), entityId, blankToNull(performedBy),
                pageable(page, size)), AuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> purchaseRequestHistory(Long purchaseRequestId, int page, int size) {
        return PageResponse.of(repository.purchaseRequestHistory(purchaseRequestId, pageable(page, size)),
                AuditLogResponse::from);
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "performedAt", "id"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
