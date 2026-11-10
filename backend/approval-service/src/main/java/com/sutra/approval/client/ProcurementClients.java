package com.sutra.approval.client;

import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.internal.InternalContracts.StatusChange;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.RemoteServiceException;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/** The RFQ, comparison and quotation services, as the approval service uses them. */
@Component
public class ProcurementClients {

    private final RestClient rfq;
    private final RestClient comparison;
    private final RestClient quotation;

    public ProcurementClients(InternalRestClients clients, ServiceUrls urls) {
        this.rfq = clients.forBaseUrl(urls.rfq());
        this.comparison = clients.forBaseUrl(urls.comparison());
        this.quotation = clients.forBaseUrl(urls.quotation());
    }

    public PurchaseRequestResponse rfq(Long id) {
        return ServiceCall.call("RFQ service", () -> rfq.get().uri("/internal/rfqs/{id}", id)
                .retrieve().body(PurchaseRequestResponse.class));
    }

    /** Moves the RFQ on behalf of {@code actor}; refused when it is no longer in {@code from}. */
    public void changeStatus(Long rfqId, PurchaseRequestStatus from, PurchaseRequestStatus to, String actor,
                             Map<String, Object> details) {
        ServiceCall.run("RFQ service", () -> rfq.post().uri("/internal/rfqs/{id}/status", rfqId)
                .body(new StatusChange(from, to, actor, details)).retrieve().toBodilessEntity());
    }

    /** The comparison report; 404 COMPARISON_REPORT_NOT_FOUND from the comparison service when it does not exist. */
    public ComparisonReportResponse report(Long reportId) {
        return ServiceCall.call("comparison service", () -> comparison.get()
                .uri("/internal/comparisons/{id}", reportId).retrieve().body(ComparisonReportResponse.class));
    }

    public Optional<ComparisonReportResponse> latestReport(Long rfqId) {
        try {
            return Optional.ofNullable(ServiceCall.call("comparison service", () -> comparison.get()
                    .uri("/internal/rfqs/{id}/comparisons/latest", rfqId).retrieve().body(ComparisonReportResponse.class)));
        } catch (RemoteServiceException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** True when a compared quotation changed after the report was generated. */
    public boolean isStale(Long reportId) {
        Boolean stale = ServiceCall.call("comparison service", () -> comparison.get()
                .uri("/internal/comparisons/{id}/stale", reportId).retrieve().body(Boolean.class));
        return Boolean.TRUE.equals(stale);
    }

    public QuotationResponse quotation(Long quotationId) {
        return ServiceCall.call("quotation service", () -> quotation.get().uri("/internal/quotations/{id}", quotationId)
                .retrieve().body(QuotationResponse.class));
    }
}
