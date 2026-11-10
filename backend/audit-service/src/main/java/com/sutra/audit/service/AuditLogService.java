package com.sutra.audit.service;

import com.sutra.audit.dto.response.AuditLogResponse;
import com.sutra.audit.dto.response.ChainVerificationResponse;
import com.sutra.common.audit.AuditEventMessage;
import com.sutra.common.web.PageResponse;

import java.util.List;

/**
 * The audit trail's use cases. There is deliberately no update or delete: entries are append-only and linked into
 * a SHA-256 hash chain, so a changed or removed entry is detected by {@link #verifyChain()}.
 */
public interface AuditLogService {

    /**
     * Stores a batch from one service's outbox. Events already stored (a repeated delivery) are skipped, so the
     * call is idempotent. Returns how many were new.
     */
    int ingest(List<AuditEventMessage> events);

    PageResponse<AuditLogResponse> search(String entityType, Long entityId, String performedBy, int page, int size);

    /** Everything that happened to an RFQ, its quotations, comparisons and award. */
    PageResponse<AuditLogResponse> purchaseRequestHistory(Long purchaseRequestId, int page, int size);

    /** Recomputes every hash in order and reports the first entry that was changed, removed or re-ordered. */
    ChainVerificationResponse verifyChain();
}
