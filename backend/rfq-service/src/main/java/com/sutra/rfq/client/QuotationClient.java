package com.sutra.rfq.client;

import com.sutra.api.internal.InternalContracts.RfqQuotationUsage;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Asks the quotation service about an RFQ's quotations. */
@Component
public class QuotationClient {

    private static final Logger log = LoggerFactory.getLogger(QuotationClient.class);
    private static final String SERVICE = "quotation service";

    private final RestClient client;

    public QuotationClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.quotation());
    }

    /** How many quotations the RFQ has, and which requested lines quotation lines are matched to. */
    public RfqQuotationUsage usage(Long rfqId) {
        return ServiceCall.call(SERVICE, () -> client.get().uri("/internal/rfqs/{id}/quotation-usage", rfqId)
                .retrieve().body(RfqQuotationUsage.class));
    }

    /**
     * Asks the quotation service to re-check the RFQ's quotations against the changed RFQ (items, currency). Called
     * after the change has committed; a failure is logged, and the quotations are re-checked on their next edit.
     */
    public void revalidate(Long rfqId) {
        try {
            ServiceCall.run(SERVICE, () -> client.post().uri("/internal/rfqs/{id}/revalidate", rfqId)
                    .retrieve().toBodilessEntity());
        } catch (RuntimeException e) {
            log.warn("Quotations of RFQ {} could not be re-checked now: {}", rfqId, e.getMessage());
        }
    }
}
