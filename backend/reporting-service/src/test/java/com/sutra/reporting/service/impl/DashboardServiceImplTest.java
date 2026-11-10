package com.sutra.reporting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sutra.api.internal.InternalContracts.AiStatus;
import com.sutra.common.error.ServiceUnavailableException;
import com.sutra.reporting.client.MasterDataClient;
import com.sutra.reporting.client.PlatformClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dashboard counts come from the services that own them; one that is down leaves only its part empty. */
class DashboardServiceImplTest {

    private final MasterDataClient masterData = mock(MasterDataClient.class);
    private final PlatformClient platform = mock(PlatformClient.class);
    private final DashboardServiceImpl service = new DashboardServiceImpl(masterData, platform);

    @BeforeEach
    void setUp() {
        when(platform.rfqCounts()).thenReturn(Map.of("OPEN", 3L, "DRAFT", 0L));
        when(platform.quotationCounts()).thenReturn(Map.of("NEEDS_REVIEW", 2L));
        when(platform.aiStatus()).thenReturn(new AiStatus(false, null, "RULES", "rules v1", true, true));
    }

    @Test
    void countsEveryStatusAndTheEngineUsedFirst() {
        when(masterData.stats()).thenReturn(new MasterDataClient.Stats(1, 4, 6));

        var d = service.summary();

        assertThat(d.purchaseRequestsByStatus()).containsEntry("OPEN", 3L);
        assertThat(d.quotationsByStatus()).containsEntry("NEEDS_REVIEW", 2L);
        assertThat(d.vendors()).isEqualTo(6L);
        assertThat(d.extractionEngine()).isEqualTo("RULES");
        assertThat(d.aiServiceAvailable()).isTrue();
    }

    @Test
    void aServiceThatIsDownLeavesItsCountsEmptyInsteadOfFailing() {
        when(masterData.stats()).thenThrow(new ServiceUnavailableException("master data service", null));
        when(platform.aiStatus()).thenThrow(new ServiceUnavailableException("extraction service", null));

        var d = service.summary();

        assertThat(d.entities()).isNull();
        assertThat(d.vendors()).isNull();
        assertThat(d.purchaseRequestsByStatus()).isNotEmpty();
        assertThat(d.aiServiceAvailable()).isFalse();
    }
}
