package com.sutra.extraction.client;

import com.sutra.api.internal.InternalContracts.ExtractionOutcome;
import com.sutra.api.internal.InternalContracts.ExtractionWork;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.RemoteServiceException;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/** The quotation service: claims a quotation for reading and receives the result. */
@Component
public class QuotationClient {

    private static final String SERVICE = "quotation service";

    private final RestClient client;

    public QuotationClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.quotation());
    }

    /**
     * Marks the quotation PROCESSING and returns what to read. Empty when it is already being read (409) or no
     * longer exists (404).
     */
    public Optional<ExtractionWork> claim(Long quotationId) {
        try {
            return Optional.ofNullable(ServiceCall.call(SERVICE, () -> client.post()
                    .uri("/internal/quotations/{id}/extraction/claim", quotationId)
                    .retrieve().body(ExtractionWork.class)));
        } catch (RemoteServiceException e) {
            if (e.getStatus() == HttpStatus.CONFLICT || e.getStatus() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Hands over the reading (or the reason it failed); the quotation service stores, recalculates and flags it. */
    public void report(Long quotationId, ExtractionOutcome outcome) {
        ServiceCall.run(SERVICE, () -> client.post().uri("/internal/quotations/{id}/extraction/result", quotationId)
                .body(outcome).retrieve().toBodilessEntity());
    }
}
