package com.sutra.quotation.client;

import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/** The RFQ service: the RFQ a quotation belongs to (status, owner, currency, requested lines). */
@Component
public class RfqClient {

    private static final String SERVICE = "RFQ service";

    private final RestClient client;

    public RfqClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.rfq());
    }

    /** The RFQ; 404 PURCHASE_REQUEST_NOT_FOUND from the RFQ service when it does not exist. */
    public PurchaseRequestResponse get(Long id) {
        return ServiceCall.call(SERVICE, () -> client.get().uri("/internal/rfqs/{id}", id)
                .retrieve().body(PurchaseRequestResponse.class));
    }

    public Map<Long, PurchaseRequestResponse> getMany(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<PurchaseRequestResponse> found = ServiceCall.call(SERVICE, () -> client.get()
                .uri(b -> b.path("/internal/rfqs").queryParam("ids", ids.toArray()).build())
                .retrieve().body(new ParameterizedTypeReference<List<PurchaseRequestResponse>>() { }));
        return found == null ? Map.of()
                : found.stream().collect(Collectors.toMap(PurchaseRequestResponse::id, Function.identity()));
    }

    /** Every open RFQ plus the ones the vendor quoted on, newest first. */
    public PageResponse<PurchaseRequestResponse> vendorView(Collection<Long> quotedIds, int page, int size) {
        return ServiceCall.call(SERVICE, () -> client.get()
                .uri(b -> b.path("/internal/rfqs/vendor-view").queryParam("quotedIds", quotedIds.toArray())
                        .queryParam("page", page).queryParam("size", size).build())
                .retrieve().body(new ParameterizedTypeReference<PageResponse<PurchaseRequestResponse>>() { }));
    }
}
