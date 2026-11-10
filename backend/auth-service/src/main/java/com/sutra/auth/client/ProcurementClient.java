package com.sutra.auth.client;

import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceUrls;
import com.sutra.common.error.ServiceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks the RFQ, quotation and approval services whether a user owns records (RFQs raised, quotations uploaded,
 * decisions made), before the user may be deleted.
 */
@Component
public class ProcurementClient {

    /** How many purchase requests and quotations a record is used by. */
    public record Usage(long purchaseRequests, long quotations) {

        public boolean inUse() {
            return purchaseRequests > 0 || quotations > 0;
        }
    }

    private final RestClient rfq;
    private final RestClient quotation;
    private final RestClient approval;

    public ProcurementClient(InternalRestClients clients, ServiceUrls urls) {
        this.rfq = clients.forBaseUrl(urls.rfq());
        this.quotation = clients.forBaseUrl(urls.quotation());
        this.approval = clients.forBaseUrl(urls.approval());
    }

    /** Fails safe: if a service cannot be asked, the caller must not delete. Decisions count as RFQs. */
    public Usage userUsage(Long userId) {
        Usage raised = usage(rfq, "RFQ service", userId);
        Usage uploaded = usage(quotation, "quotation service", userId);
        Usage decided = usage(approval, "approval service", userId);
        return new Usage(raised.purchaseRequests() + decided.purchaseRequests(), uploaded.quotations());
    }

    private static Usage usage(RestClient client, String service, Long userId) {
        try {
            Usage usage = client.get().uri("/internal/usage/users/{id}", userId).retrieve().body(Usage.class);
            if (usage == null) {
                throw new ServiceUnavailableException(service, null);
            }
            return usage;
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(service, e);
        }
    }
}
