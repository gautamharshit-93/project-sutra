package com.sutra.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.common.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** Audit events go to the outbox, grouped by RFQ, in the caller's transaction. */
class AuditServiceTest {

    private final AuditOutboxRepository outbox = mock(AuditOutboxRepository.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final AuditService audit = new AuditService(outbox, currentUser, JsonMapper.builder().build(),
            (type, id) -> "QUOTATION".equals(type) ? 77L : null);

    @Test
    void eventIsWrittenToTheOutboxWithItsRfqAndPerformer() {
        when(currentUser.username()).thenReturn("asha.buyer");
        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));

        audit.record("QUOTATION", 5L, "UPLOADED", AuditService.details("files", 3, "vendor", null));

        ArgumentCaptor<AuditOutboxEvent> saved = ArgumentCaptor.forClass(AuditOutboxEvent.class);
        verify(outbox).save(saved.capture());
        AuditOutboxEvent e = saved.getValue();
        assertThat(e.getEventId()).isNotNull();
        assertThat(e.getPurchaseRequestId()).isEqualTo(77L);
        assertThat(e.getPerformedBy()).isEqualTo("asha.buyer");
        assertThat(e.getDetails()).isEqualTo("{\"files\":3,\"vendor\":null}");
        assertThat(e.toMessage("procurement-service").service()).isEqualTo("procurement-service");
    }

    @Test
    void emptyDetailsAreStoredAsNull() {
        when(outbox.save(any())).thenAnswer(inv -> inv.getArgument(0));

        audit.recordAs("system", "USER", 1L, "LOGIN", null);

        ArgumentCaptor<AuditOutboxEvent> saved = ArgumentCaptor.forClass(AuditOutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getDetails()).isNull();
        assertThat(saved.getValue().getPurchaseRequestId()).isNull();
    }
}
