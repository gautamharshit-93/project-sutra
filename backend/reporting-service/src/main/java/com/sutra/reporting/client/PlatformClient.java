package com.sutra.reporting.client;

import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.internal.InternalContracts.AiCompletion;
import com.sutra.api.internal.InternalContracts.AiCompletionRequest;
import com.sutra.api.internal.InternalContracts.AiStatus;
import com.sutra.api.internal.InternalContracts.StatusCounts;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationSummary;
import com.sutra.api.rfq.PurchaseRequestRequests.RfqTextRequest;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.api.rfq.RfqDraftResponses.RfqDraft;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import com.sutra.common.web.PageResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the reporting service reads, from the services that own it (their {@code /internal} APIs, with a
 * service token). The public endpoints of this service are role-checked; these calls only fetch data.
 */
@Component
public class PlatformClient {

    private static final ParameterizedTypeReference<List<Long>> IDS = new ParameterizedTypeReference<>() { };

    private final RestClient rfq;
    private final RestClient quotation;
    private final RestClient comparison;
    private final RestClient approval;
    private final RestClient extraction;

    public PlatformClient(InternalRestClients clients, ServiceUrls urls) {
        this.rfq = clients.forBaseUrl(urls.rfq());
        this.quotation = clients.forBaseUrl(urls.quotation());
        this.comparison = clients.forBaseUrl(urls.comparison());
        this.approval = clients.forBaseUrl(urls.approval());
        this.extraction = clients.forBaseUrl(urls.extraction());
    }

    // ---------- RFQs ----------

    public PurchaseRequestResponse rfq(Long id) {
        return ServiceCall.call("RFQ service", () -> rfq.get().uri("/internal/rfqs/{id}", id)
                .retrieve().body(PurchaseRequestResponse.class));
    }

    public PageResponse<PurchaseRequestSummary> searchRfqs(String q, PurchaseRequestStatus status, int page, int size) {
        return ServiceCall.call("RFQ service", () -> rfq.get().uri(b -> b.path("/internal/rfqs/search")
                        .queryParamIfPresent("q", Optional.ofNullable(q)).queryParamIfPresent("status", Optional.ofNullable(status))
                        .queryParam("page", page).queryParam("size", size).build())
                .retrieve().body(new ParameterizedTypeReference<PageResponse<PurchaseRequestSummary>>() { }));
    }

    public Map<String, Long> rfqCounts() {
        StatusCounts counts = ServiceCall.call("RFQ service", () -> rfq.get().uri("/internal/rfqs/stats")
                .retrieve().body(StatusCounts.class));
        return counts == null ? Map.of() : counts.byStatus();
    }

    /** Open RFQs raised by the user (every buyer when null). */
    public List<Long> openRfqIds(Long requestedById) {
        return ServiceCall.call("RFQ service", () -> rfq.get().uri(b -> b.path("/internal/rfqs/open-ids")
                .queryParamIfPresent("requestedById", Optional.ofNullable(requestedById)).build()).retrieve().body(IDS));
    }

    public long awaitingAward(Long excludeRequestedById) {
        Long n = ServiceCall.call("RFQ service", () -> rfq.get().uri(b -> b.path("/internal/rfqs/awaiting-award-count")
                .queryParamIfPresent("excludeRequestedById", Optional.ofNullable(excludeRequestedById)).build())
                .retrieve().body(Long.class));
        return n == null ? 0 : n;
    }

    /** Every open RFQ plus the ones the vendor quoted on. */
    public PageResponse<PurchaseRequestResponse> vendorView(Collection<Long> quotedIds, int page, int size) {
        return ServiceCall.call("RFQ service", () -> rfq.get().uri(b -> b.path("/internal/rfqs/vendor-view")
                        .queryParam("quotedIds", quotedIds.toArray()).queryParam("page", page).queryParam("size", size).build())
                .retrieve().body(new ParameterizedTypeReference<PageResponse<PurchaseRequestResponse>>() { }));
    }

    public List<PurchaseRequestResponse> rfqs(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return ServiceCall.call("RFQ service", () -> rfq.get().uri(b -> b.path("/internal/rfqs")
                .queryParam("ids", ids.toArray()).build())
                .retrieve().body(new ParameterizedTypeReference<List<PurchaseRequestResponse>>() { }));
    }

    // ---------- quotations ----------

    public List<QuotationSummary> quotationSummaries(Long rfqId) {
        return ServiceCall.call("quotation service", () -> quotation.get()
                .uri("/internal/rfqs/{id}/quotation-summaries", rfqId)
                .retrieve().body(new ParameterizedTypeReference<List<QuotationSummary>>() { }));
    }

    public QuotationResponse quotation(Long id) {
        return ServiceCall.call("quotation service", () -> quotation.get().uri("/internal/quotations/{id}", id)
                .retrieve().body(QuotationResponse.class));
    }

    public Map<String, Long> quotationCounts() {
        StatusCounts counts = ServiceCall.call("quotation service", () -> quotation.get()
                .uri("/internal/quotations/stats").retrieve().body(StatusCounts.class));
        return counts == null ? Map.of() : counts.byStatus();
    }

    /** Of these RFQs, those with a quotation ready to compare. */
    public List<Long> rfqsWithReadyQuotations(Collection<Long> rfqIds) {
        if (rfqIds.isEmpty()) {
            return List.of();
        }
        return ServiceCall.call("quotation service", () -> quotation.get()
                .uri(b -> b.path("/internal/rfqs/with-ready-quotations").queryParam("rfqIds", rfqIds.toArray()).build())
                .retrieve().body(IDS));
    }

    /** The vendor's own quotations and the RFQs they are for. */
    public List<com.sutra.api.internal.InternalContracts.QuotationRef> quotationsOf(Long vendorId) {
        return ServiceCall.call("quotation service", () -> quotation.get()
                .uri("/internal/vendors/{id}/quotation-refs", vendorId)
                .retrieve().body(new ParameterizedTypeReference<List<com.sutra.api.internal.InternalContracts.QuotationRef>>() { }));
    }

    // ---------- comparisons, approvals, AI ----------

    public List<ComparisonReportResponse> comparisons(Long rfqId) {
        return ServiceCall.call("comparison service", () -> comparison.get().uri("/internal/rfqs/{id}/comparisons", rfqId)
                .retrieve().body(new ParameterizedTypeReference<List<ComparisonReportResponse>>() { }));
    }

    public ComparisonReportResponse comparison(Long reportId) {
        return ServiceCall.call("comparison service", () -> comparison.get().uri("/internal/comparisons/{id}", reportId)
                .retrieve().body(ComparisonReportResponse.class));
    }

    /** The newest decision on the RFQ, if there is one. */
    public Optional<ApprovalResponse> latestDecision(Long rfqId) {
        List<ApprovalResponse> found = ServiceCall.call("approval service", () -> approval.get()
                .uri("/internal/rfqs/{id}/approvals", rfqId)
                .retrieve().body(new ParameterizedTypeReference<List<ApprovalResponse>>() { }));
        return found == null ? Optional.empty() : found.stream().findFirst();
    }

    public AiStatus aiStatus() {
        return ServiceCall.call("extraction service", () -> extraction.get().uri("/internal/ai/status")
                .retrieve().body(AiStatus.class));
    }

    public AiCompletion complete(AiCompletionRequest request) {
        return ServiceCall.call("extraction service", () -> extraction.post().uri("/internal/ai/complete")
                .body(request).retrieve().body(AiCompletion.class));
    }

    public RfqDraft rfqDraftFromText(String text) {
        return ServiceCall.call("extraction service", () -> extraction.post().uri("/internal/rfq-drafts/from-text")
                .body(new RfqTextRequest(text)).retrieve().body(RfqDraft.class));
    }
}
