package com.sutra.auth.client;

import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceUrls;
import com.sutra.common.error.ServiceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks the procurement service whether a user owns records, before the user may be deleted. */
@Component
public class ProcurementClient {

    /** How many purchase requests and quotations a record is used by. */
    public record Usage(long purchaseRequests, long quotations) {

        public boolean inUse() {
            return purchaseRequests > 0 || quotations > 0;
        }
    }

    private final RestClient client;

    public ProcurementClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.procurement());
    }

    /** Fails safe: if procurement cannot be asked, the caller must not delete. */
    public Usage userUsage(Long userId) {
        try {
            Usage usage = client.get().uri("/internal/usage/users/{id}", userId).retrieve().body(Usage.class);
            if (usage == null) {
                throw new ServiceUnavailableException("procurement service", null);
            }
            return usage;
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("procurement service", e);
        }
    }
}
