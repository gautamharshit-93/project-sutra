package com.sutra.comparison.client;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.internal.InternalContracts.StatusChange;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** The RFQ and quotation services, as the comparison service uses them. */
@Component
public class ProcurementClients {

    private final RestClient rfq;
    private final RestClient quotation;

    public ProcurementClients(InternalRestClients clients, ServiceUrls urls) {
        this.rfq = clients.forBaseUrl(urls.rfq());
        this.quotation = clients.forBaseUrl(urls.quotation());
    }

    public PurchaseRequestResponse rfq(Long id) {
        return ServiceCall.call("RFQ service", () -> rfq.get().uri("/internal/rfqs/{id}", id)
                .retrieve().body(PurchaseRequestResponse.class));
    }

    /** Moves the RFQ on behalf of {@code actor}; refused when it is no longer in {@code from}. */
    public PurchaseRequestResponse changeStatus(Long rfqId, PurchaseRequestStatus from, PurchaseRequestStatus to,
                                               String actor, Map<String, Object> details) {
        return ServiceCall.call("RFQ service", () -> rfq.post().uri("/internal/rfqs/{id}/status", rfqId)
                .body(new StatusChange(from, to, actor, details)).retrieve().body(PurchaseRequestResponse.class));
    }

    /** Every quotation of the RFQ, in full: lines, flags, terms and version. */
    public List<QuotationResponse> quotations(Long rfqId) {
        return ServiceCall.call("quotation service", () -> quotation.get().uri("/internal/rfqs/{id}/quotations", rfqId)
                .retrieve().body(new ParameterizedTypeReference<List<QuotationResponse>>() { }));
    }

    /** The quotations' current versions (to tell whether a saved comparison is stale). */
    public Map<Long, Long> versions(Collection<Long> quotationIds) {
        return ServiceCall.call("quotation service", () -> quotation.get()
                .uri(b -> b.path("/internal/quotations/versions").queryParam("ids", quotationIds.toArray()).build())
                .retrieve().body(new ParameterizedTypeReference<Map<Long, Long>>() { }));
    }
}
