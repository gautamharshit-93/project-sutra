package com.sutra.service;

import com.sutra.ai.DocumentProcessingClient;
import com.sutra.ai.ExtractionEngines;
import com.sutra.ai.QuotationExtractor;
import com.sutra.entity.enums.PurchaseRequestStatus;
import com.sutra.entity.enums.QuotationStatus;
import com.sutra.repository.BusinessEntityRepository;
import com.sutra.repository.CustomerRepository;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.repository.QuotationRepository;
import com.sutra.repository.VendorRepository;
import com.sutra.response.DecisionResponses.DashboardResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final BusinessEntityRepository entityRepository;
    private final CustomerRepository customerRepository;
    private final VendorRepository vendorRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final QuotationRepository quotationRepository;
    private final ExtractionEngines engines;
    private final DocumentProcessingClient documentClient;

    public DashboardService(BusinessEntityRepository entityRepository, CustomerRepository customerRepository,
                            VendorRepository vendorRepository, PurchaseRequestRepository purchaseRequestRepository,
                            QuotationRepository quotationRepository, ExtractionEngines engines,
                            DocumentProcessingClient documentClient) {
        this.entityRepository = entityRepository;
        this.customerRepository = customerRepository;
        this.vendorRepository = vendorRepository;
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
        // The engine AUTO would use right now (the rule-based reader when no AI engine is configured)
        QuotationExtractor extractor = engines.firstAvailable();
        return new DashboardResponse(entityRepository.count(), customerRepository.count(), vendorRepository.count(),
                prs, quotations, extractor == null ? "NONE" : String.valueOf(extractor.source()),
                extractor == null ? "none" : extractor.model(), extractor != null, documentClient.isAvailable());
    }
}
