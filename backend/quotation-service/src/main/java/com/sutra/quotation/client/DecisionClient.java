package com.sutra.quotation.client;

import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.internal.InternalContracts.ExtractionTask;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The services downstream of a quotation: extraction (reading), comparison (reports) and approval (awards). */
@Component
public class DecisionClient {

    private final RestClient extraction;
    private final RestClient comparison;
    private final RestClient approval;

    public DecisionClient(InternalRestClients clients, ServiceUrls urls) {
        this.extraction = clients.forBaseUrl(urls.extraction());
        this.comparison = clients.forBaseUrl(urls.comparison());
        this.approval = clients.forBaseUrl(urls.approval());
    }

    /** Refuses an engine/model that cannot be used (e.g. GROQ without a key), before anything is stored. */
    public void checkEngine(ExtractionChoice choice) {
        ServiceCall.run("extraction service", () -> extraction.post().uri("/internal/extractions/check")
                .body(new ExtractionTask(null, choice.engine(), choice.model())).retrieve().toBodilessEntity());
    }

    /** Queues a reading of the quotation. */
    public void read(Long quotationId, ExtractionChoice choice) {
        ServiceCall.run("extraction service", () -> extraction.post().uri("/internal/extractions")
                .body(new ExtractionTask(quotationId, choice.engine(), choice.model())).retrieve().toBodilessEntity());
    }

    /** How many saved comparison reports include the quotation (it is then kept for the audit trail). */
    public long comparisonReferences(Long quotationId) {
        Long count = ServiceCall.call("comparison service", () -> comparison.get()
                .uri("/internal/quotations/{id}/comparison-count", quotationId).retrieve().body(Long.class));
        return count == null ? 0 : count;
    }

    /** The latest decision of each RFQ that has one. */
    public Map<Long, ApprovalResponse> latestDecisions(Collection<Long> rfqIds) {
        if (rfqIds.isEmpty()) {
            return Map.of();
        }
        List<ApprovalResponse> found = ServiceCall.call("approval service", () -> approval.get()
                .uri(b -> b.path("/internal/approvals/latest").queryParam("rfqIds", rfqIds.toArray()).build())
                .retrieve().body(new ParameterizedTypeReference<List<ApprovalResponse>>() { }));
        return found == null ? Map.of()
                : found.stream().collect(Collectors.toMap(ApprovalResponse::purchaseRequestId, Function.identity()));
    }
}
