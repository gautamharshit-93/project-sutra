package com.sutra.procurement.service;

import com.sutra.common.error.ServiceUnavailableException;
import com.sutra.procurement.ai.DocumentProcessingClient;
import com.sutra.procurement.client.MasterDataClient;
import com.sutra.procurement.ai.ExtractionEngines;
import com.sutra.procurement.ai.QuotationExtractor;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.repository.PurchaseRequestRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.response.DecisionResponses.DashboardResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Counts for the dashboard. Master-data counts come from the master data service and are null if it is down. */
@Service
public class DashboardService {

    private final MasterDataClient masterData;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final QuotationRepository quotationRepository;
    private final ExtractionEngines engines;
    private final DocumentProcessingClient documentClient;

    public DashboardService(MasterDataClient masterData, PurchaseRequestRepository purchaseRequestRepository,
                            QuotationRepository quotationRepository, ExtractionEngines engines,
                            DocumentProcessingClient documentClient) {
        this.masterData = masterData;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.quotationRepository = quotationRepository;
        this.engines = engines;
        this.documentClient = documentClient;
    }

    @Transactional(readOnly = true)
    public DashboardResponse summary() {
        Map<String, Long> prs = new LinkedHashMap<>();
        for (PurchaseRequestStatus s : PurchaseRequestStatus.values()) {
            prs.put(s.name(), purchaseRequestRepository.countByStatus(s));
        }
        Map<String, Long> quotations = new LinkedHashMap<>();
        for (QuotationStatus s : QuotationStatus.values()) {
            quotations.put(s.name(), quotationRepository.countByStatus(s));
        }
        MasterDataClient.Stats stats;
        try {
            stats = masterData.stats();
        } catch (ServiceUnavailableException e) {
            stats = null;
        }
        // The engine AUTO would use right now (the rule-based reader when Gemini is not configured)
        QuotationExtractor extractor = engines.firstAvailable();
        return new DashboardResponse(stats == null ? null : stats.entities(), stats == null ? null : stats.customers(),
                stats == null ? null : stats.vendors(), prs, quotations,
                extractor == null ? "NONE" : String.valueOf(extractor.source()),
                extractor == null ? "none" : extractor.model(), extractor != null, documentClient.isAvailable());
    }
}
