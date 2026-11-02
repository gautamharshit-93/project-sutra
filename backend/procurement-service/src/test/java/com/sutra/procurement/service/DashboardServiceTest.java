package com.sutra.procurement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sutra.common.error.ServiceUnavailableException;
import com.sutra.procurement.ai.DocumentProcessingClient;
import com.sutra.procurement.ai.ExtractionEngines;
import com.sutra.procurement.ai.QuotationExtractor;
import com.sutra.procurement.client.MasterDataClient;
import com.sutra.procurement.config.ExtractionProperties;
import com.sutra.procurement.entity.enums.ExtractionSource;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.repository.PurchaseRequestRepository;
import com.sutra.procurement.repository.QuotationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Dashboard counts come from this service's data and the master data service's statistics. */
class DashboardServiceTest {

    private final MasterDataClient masterData = mock(MasterDataClient.class);
    private final PurchaseRequestRepository prs = mock(PurchaseRequestRepository.class);
    private final QuotationRepository quotations = mock(QuotationRepository.class);
    private final DocumentProcessingClient documents = mock(DocumentProcessingClient.class);
    private final QuotationExtractor rules = mock(QuotationExtractor.class);

    private DashboardService service() {
        when(rules.source()).thenReturn(ExtractionSource.RULES);
        when(rules.model()).thenReturn("rules v1");
        when(rules.isAvailable()).thenReturn(true);
        return new DashboardService(masterData, prs, quotations,
                new ExtractionEngines(List.of(rules), ExtractionProperties.defaults()), documents);
    }

    @Test
    void countsEveryStatusAndTheEngineUsedFirst() {
        when(prs.countByStatus(any())).thenReturn(0L);
        when(prs.countByStatus(PurchaseRequestStatus.OPEN)).thenReturn(3L);
        when(quotations.countByStatus(QuotationStatus.NEEDS_REVIEW)).thenReturn(2L);
        when(masterData.stats()).thenReturn(new MasterDataClient.Stats(1, 4, 6));
        when(documents.isAvailable()).thenReturn(true);

        var d = service().summary();

        assertThat(d.purchaseRequestsByStatus()).containsEntry("OPEN", 3L).hasSize(PurchaseRequestStatus.values().length);
        assertThat(d.quotationsByStatus()).containsEntry("NEEDS_REVIEW", 2L);
        assertThat(d.vendors()).isEqualTo(6L);
        assertThat(d.extractionEngine()).isEqualTo("RULES");
        assertThat(d.aiServiceAvailable()).isTrue();
    }

    @Test
    void masterDataServiceDownLeavesItsCountsEmptyInsteadOfFailing() {
        when(masterData.stats()).thenThrow(new ServiceUnavailableException("master data service", null));

        var d = service().summary();

        assertThat(d.entities()).isNull();
        assertThat(d.vendors()).isNull();
        assertThat(d.purchaseRequestsByStatus()).isNotEmpty();
    }
}
