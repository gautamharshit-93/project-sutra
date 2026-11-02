package com.sutra.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.audit.entity.AuditLog;
import com.sutra.audit.repository.AuditLogRepository;
import com.sutra.common.audit.AuditEventMessage;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Storing events from the services' outboxes. */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock AuditLogRepository repository;
    @InjectMocks AuditLogService service;

    private static AuditEventMessage event(UUID id, String action) {
        return new AuditEventMessage(id, "procurement-service", "QUOTATION", 5L, 9L, action, "asha.buyer",
                Instant.parse("2026-11-01T10:00:00Z"), "{\"files\":2}");
    }

    @Test
    void newEventsAreStoredWithTheirRfq() {
        UUID id = UUID.randomUUID();
        when(repository.findExistingEventIds(any())).thenReturn(List.of());

        int stored = service.ingest(List.of(event(id, "UPLOADED")));

        assertThat(stored).isEqualTo(1);
        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo(id);
        assertThat(saved.getValue().getPurchaseRequestId()).isEqualTo(9L);
        assertThat(saved.getValue().getService()).isEqualTo("procurement-service");
        assertThat(saved.getValue().getPerformedAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
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
        int stored = service.ingest(Arrays.asList(null, new AuditEventMessage(null, "x", "Q", 1L, null, "A", "u", null, null)));

        assertThat(stored).isZero();
        verify(repository, never()).save(any());
    }
}
