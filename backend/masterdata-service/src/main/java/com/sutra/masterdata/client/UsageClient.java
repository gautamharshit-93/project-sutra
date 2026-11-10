package com.sutra.masterdata.client;

import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceUrls;
import com.sutra.common.error.ServiceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks the services that reference master data whether a record is still in use, before it may be deleted:
 * the RFQ service (entities, sub-entities, customers), the quotation service (vendors) and auth (vendor logins). Fails safe: when a service cannot be
 * asked, the delete is refused.
 */
@Component
public class UsageClient {

    /** How many purchase requests and quotations reference a record. */
    public record Usage(long purchaseRequests, long quotations) {

        public boolean inUse() {
            return purchaseRequests > 0 || quotations > 0;
        }
    }

    /** How many user logins are linked to a vendor. */
    public record VendorLogins(long users) {
    }

    private final RestClient rfq;
    private final RestClient quotation;
    private final RestClient auth;

    public UsageClient(InternalRestClients clients, ServiceUrls urls) {
        this.rfq = clients.forBaseUrl(urls.rfq());
        this.quotation = clients.forBaseUrl(urls.quotation());
        this.auth = clients.forBaseUrl(urls.auth());
    }

    /** {@code kind} is entities, sub-entities, customers or vendors. */
    public Usage procurementUsage(String kind, Long id) {
        boolean vendors = "vendors".equals(kind);
        RestClient owner = vendors ? quotation : rfq;
        String service = vendors ? "quotation service" : "RFQ service";
        try {
            Usage usage = owner.get().uri("/internal/usage/{kind}/{id}", kind, id).retrieve().body(Usage.class);
            if (usage == null) {
                throw new ServiceUnavailableException(service, null);
            }
            return usage;
        } catch (RestClientException e) {
            throw new ServiceUnavailableException(service, e);
        }
    }

    public long vendorLogins(Long vendorId) {
        try {
            VendorLogins logins = auth.get().uri("/internal/vendors/{id}/logins", vendorId).retrieve().body(VendorLogins.class);
            if (logins == null) {
                throw new ServiceUnavailableException("auth service", null);
            }
            return logins.users();
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("auth service", e);
        }
    }
}
