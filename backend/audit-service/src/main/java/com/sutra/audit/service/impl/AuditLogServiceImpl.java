package com.sutra.audit.service.impl;

import com.sutra.audit.dto.response.AuditLogResponse;
import com.sutra.audit.dto.response.ChainVerificationResponse;
import com.sutra.audit.entity.AuditChainHead;
import com.sutra.audit.entity.AuditLog;
import com.sutra.audit.repository.AuditChainHeadRepository;
import com.sutra.audit.repository.AuditLogRepository;
import com.sutra.audit.service.AuditLogService;
import com.sutra.audit.service.internal.AuditHashCalculator;
import com.sutra.common.audit.AuditEventMessage;
import com.sutra.common.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stores audit events sent by the services and answers audit-trail queries. Every new entry is linked to the
 * previous one with a SHA-256 hash while the chain head is locked, so the chain never forks.
 */
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogServiceImpl.class);
    private static final int VERIFY_PAGE = 1000;

    private final AuditLogRepository repository;
    private final AuditChainHeadRepository headRepository;
    private final AuditHashCalculator hashCalculator;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AuditLogServiceImpl(AuditLogRepository repository, AuditChainHeadRepository headRepository,
                               AuditHashCalculator hashCalculator, TransactionTemplate tx, Clock clock) {
        this.repository = repository;
        this.headRepository = headRepository;
        this.hashCalculator = hashCalculator;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int ingest(List<AuditEventMessage> events) {
        List<AuditEventMessage> valid = events.stream().filter(Objects::nonNull)
                .filter(e -> e.eventId() != null && e.entityType() != null && e.action() != null)
                .toList();
        if (valid.isEmpty()) {
            return 0;
        }
        // Inbox: event ids already stored are skipped, so a repeated delivery is harmless.
        Set<UUID> seen = new HashSet<>(repository.findExistingEventIds(valid.stream().map(AuditEventMessage::eventId).toList()));
        AuditChainHead head = null;
        int stored = 0;
        for (AuditEventMessage e : valid) {
            if (!seen.add(e.eventId())) {
                continue;
            }
            if (head == null) {
                head = headRepository.lockHead();
            }
            AuditLog entry = toEntry(e);
            link(entry, head);
            repository.save(entry);
            stored++;
        }
        return stored;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String entityType, Long entityId, String performedBy, int page, int size) {
        return PageResponse.of(repository.search(blankToNull(entityType), entityId, blankToNull(performedBy),
                pageable(page, size)), AuditLogResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> purchaseRequestHistory(Long purchaseRequestId, int page, int size) {
        return PageResponse.of(repository.purchaseRequestHistory(purchaseRequestId, pageable(page, size)),
                AuditLogResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public ChainVerificationResponse verifyChain() {
        long unchained = repository.countBySequenceNoIsNull();
        String previous = AuditHashCalculator.GENESIS_HASH;
        long expectedSequence = 1;
        long checked = 0;
        long after = 0;
        List<AuditLog> page;
        do {
            page = repository.findBySequenceNoGreaterThanOrderBySequenceNoAsc(after, PageRequest.of(0, VERIFY_PAGE));
            for (AuditLog entry : page) {
                checked++;
                if (entry.getSequenceNo() != expectedSequence) {
                    return broken(checked, expectedSequence, "entry " + expectedSequence + " is missing", previous);
                }
                if (!previous.equals(entry.getPreviousHash())) {
                    return broken(checked, entry.getSequenceNo(), "link to the previous entry does not match", previous);
                }
                String recomputed = hashCalculator.hash(entry, previous);
                if (!recomputed.equals(entry.getHash())) {
                    return broken(checked, entry.getSequenceNo(), "content was changed after it was stored", previous);
                }
                previous = entry.getHash();
                expectedSequence++;
                after = entry.getSequenceNo();
            }
        } while (page.size() == VERIFY_PAGE);
        if (unchained > 0) {
            return broken(checked, null, unchained + " entries are not linked into the chain yet", previous);
        }
        return new ChainVerificationResponse(true, checked, null, null, previous, Instant.now(clock));
    }

    /**
     * Links entries stored before the hash chain existed (oldest first), once, when the service starts. New
     * entries are always linked as they arrive.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void linkUnchainedEntries() {
        long total = 0;
        int linked;
        do {
            Integer count = tx.execute(status -> {
                List<AuditLog> legacy = repository.findTop500BySequenceNoIsNullOrderByIdAsc();
                if (legacy.isEmpty()) {
                    return 0;
                }
                AuditChainHead head = headRepository.lockHead();
                legacy.forEach(entry -> {
                    entry.setPerformedAt(AuditHashCalculator.normalise(entry.getPerformedAt()));
                    link(entry, head);
                });
                return legacy.size();
            });
            linked = count == null ? 0 : count;
            total += linked;
        } while (linked > 0);
        if (total > 0) {
            log.info("Linked {} earlier audit entries into the hash chain", total);
        }
    }

    private void link(AuditLog entry, AuditChainHead head) {
        long sequence = head.getLastSequence() + 1;
        entry.setSequenceNo(sequence);
        entry.setPreviousHash(head.getLastHash());
        entry.setHash(hashCalculator.hash(entry, head.getLastHash()));
        head.advance(sequence, entry.getHash());
    }

    private static AuditLog toEntry(AuditEventMessage e) {
        AuditLog entry = new AuditLog();
        entry.setEventId(e.eventId());
        entry.setService(truncate(e.service() == null ? "unknown" : e.service(), 40));
        entry.setEntityType(truncate(e.entityType(), 50));
        entry.setEntityId(e.entityId());
        entry.setPurchaseRequestId(e.purchaseRequestId());
        entry.setAction(truncate(e.action(), 50));
        entry.setPerformedBy(truncate(e.performedBy() == null ? "system" : e.performedBy(), 60));
        entry.setPerformedAt(AuditHashCalculator.normalise(e.performedAt() == null ? Instant.now() : e.performedAt()));
        entry.setCorrelationId(e.correlationId() == null ? null : truncate(e.correlationId(), 64));
        entry.setDetails(e.details());
        return entry;
    }

    private static ChainVerificationResponse broken(long checked, Long sequence, String reason, String lastGoodHash) {
        log.error("Audit chain verification failed at entry {}: {}", sequence, reason);
        return new ChainVerificationResponse(false, checked, sequence, reason, lastGoodHash, Instant.now());
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, AuditLog::getPerformedAt, AuditLog::getId));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
