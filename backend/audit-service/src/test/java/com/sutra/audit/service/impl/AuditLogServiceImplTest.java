package com.sutra.audit.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.audit.dto.response.ChainVerificationResponse;
import com.sutra.audit.entity.AuditChainHead;
import com.sutra.audit.entity.AuditLog;
import com.sutra.audit.repository.AuditChainHeadRepository;
import com.sutra.audit.repository.AuditLogRepository;
import com.sutra.audit.service.internal.AuditHashCalculator;
import com.sutra.common.audit.AuditEventMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

/** Storing events from the services' outboxes, and the tamper-evident hash chain. */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock AuditLogRepository repository;
    @Mock AuditChainHeadRepository headRepository;
    @Mock TransactionTemplate tx;

    private final AuditHashCalculator hashCalculator = new AuditHashCalculator();
    private AuditChainHead head;
    private AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        head = new AuditChainHead(0, AuditHashCalculator.GENESIS_HASH);
        lenient().when(headRepository.lockHead()).thenReturn(head);
        service = new AuditLogServiceImpl(repository, headRepository, hashCalculator, tx,
                Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC));
    }

    private static AuditEventMessage event(UUID id, String action) {
        return new AuditEventMessage(id, "procurement-service", "QUOTATION", 5L, 9L, action, "asha.buyer",
                Instant.parse("2026-11-01T10:00:00.123456789Z"), "{\"files\":2,\"name\":\"q.pdf\"}", "corr-12345678");
    }

    @Test
    void newEventsAreStoredWithTheirRfqAndCorrelationId() {
        UUID id = UUID.randomUUID();
        when(repository.findExistingEventIds(any())).thenReturn(List.of());

        int stored = service.ingest(List.of(event(id, "UPLOADED")));

        assertThat(stored).isEqualTo(1);
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(saved.capture());
        AuditLog entry = saved.getValue();
        assertThat(entry.getEventId()).isEqualTo(id);
        assertThat(entry.getPurchaseRequestId()).isEqualTo(9L);
        assertThat(entry.getService()).isEqualTo("procurement-service");
        assertThat(entry.getCorrelationId()).isEqualTo("corr-12345678");
        assertThat(entry.getPerformedAt()).as("cut to the microseconds PostgreSQL keeps")
                .isEqualTo(Instant.parse("2026-11-01T10:00:00.123456Z"));
    }

    @Test
    void entriesAreLinkedIntoTheHashChainInOrder() {
        when(repository.findExistingEventIds(any())).thenReturn(List.of());

        service.ingest(List.of(event(UUID.randomUUID(), "UPLOADED"), event(UUID.randomUUID(), "EXTRACTED")));

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, times(2)).save(saved.capture());
        AuditLog first = saved.getAllValues().get(0);
        AuditLog second = saved.getAllValues().get(1);
        assertThat(first.getSequenceNo()).isEqualTo(1L);
        assertThat(first.getPreviousHash()).isEqualTo(AuditHashCalculator.GENESIS_HASH);
        assertThat(second.getSequenceNo()).isEqualTo(2L);
        assertThat(second.getPreviousHash()).isEqualTo(first.getHash());
        assertThat(first.getHash()).hasSize(64).isNotEqualTo(second.getHash());
        assertThat(head.getLastSequence()).isEqualTo(2L);
        assertThat(head.getLastHash()).isEqualTo(second.getHash());
    }

    @Test
    void repeatedDeliveryIsHarmless() {
        UUID known = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        when(repository.findExistingEventIds(any())).thenReturn(List.of(known));

        int stored = service.ingest(List.of(event(known, "UPLOADED"), event(fresh, "EXTRACTED"), event(fresh, "EXTRACTED")));

        assertThat(stored).as("the known event and the duplicate in the batch are skipped").isEqualTo(1);
        verify(repository, times(1)).save(any());
    }

    @Test
    void malformedEventsAreIgnored() {
        int stored = service.ingest(Arrays.asList(null,
                new AuditEventMessage(null, "x", "Q", 1L, null, "A", "u", null, null, null)));

        assertThat(stored).isZero();
        verify(repository, never()).save(any());
        verify(headRepository, never()).lockHead();
    }

    @Test
    void anIntactChainVerifies() {
        List<AuditLog> chain = storedChain(3);
        when(repository.findBySequenceNoGreaterThanOrderBySequenceNoAsc(eq(0L), any())).thenReturn(chain);

        ChainVerificationResponse result = service.verifyChain();

        assertThat(result.intact()).isTrue();
        assertThat(result.checked()).isEqualTo(3);
        assertThat(result.lastHash()).isEqualTo(chain.get(2).getHash());
    }

    @Test
    void aChangedEntryIsDetected() {
        List<AuditLog> chain = storedChain(3);
        chain.get(1).setPerformedBy("someone.else"); // edited directly in the database
        when(repository.findBySequenceNoGreaterThanOrderBySequenceNoAsc(eq(0L), any())).thenReturn(chain);

        ChainVerificationResponse result = service.verifyChain();

        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSequence()).isEqualTo(2L);
        assertThat(result.reason()).contains("changed");
    }

    @Test
    void aRemovedEntryIsDetected() {
        List<AuditLog> chain = storedChain(3);
        chain.remove(1);
        when(repository.findBySequenceNoGreaterThanOrderBySequenceNoAsc(eq(0L), any())).thenReturn(chain);

        ChainVerificationResponse result = service.verifyChain();

        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSequence()).isEqualTo(2L);
        assertThat(result.reason()).contains("missing");
    }

    @Test
    void detailsReadBackFromJsonbInADifferentKeyOrderStillVerify() {
        List<AuditLog> chain = storedChain(1);
        chain.get(0).setDetails("{\"name\": \"q.pdf\", \"files\": 2}"); // how PostgreSQL returns the jsonb
        when(repository.findBySequenceNoGreaterThanOrderBySequenceNoAsc(eq(0L), any())).thenReturn(chain);

        assertThat(service.verifyChain().intact()).isTrue();
    }

    /** Entries linked exactly as {@code ingest} links them. */
    private List<AuditLog> storedChain(int size) {
        when(repository.findExistingEventIds(any())).thenReturn(List.of());
        List<AuditEventMessage> events = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            events.add(event(UUID.randomUUID(), "STEP_" + i));
        }
        service.ingest(events);
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, times(size)).save(saved.capture());
        lenient().when(repository.countBySequenceNoIsNull()).thenReturn(0L);
        lenient().when(repository.findBySequenceNoGreaterThanOrderBySequenceNoAsc(anyLong(), any())).thenReturn(List.of());
        return new ArrayList<>(saved.getAllValues());
    }
}
