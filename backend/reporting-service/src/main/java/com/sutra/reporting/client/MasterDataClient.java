package com.sutra.reporting.client;

import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceUrls;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.error.ServiceUnavailableException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the master data (entities, sub-entities, customers, vendors) that procurement records refer to. Records
 * keep the id and the name returned here; nothing else of master data is copied.
 */
@Component
public class MasterDataClient {

    public record EntitySnapshot(Long id, String code, String name, boolean active) {
    }

    public record SubEntitySnapshot(Long id, Long entityId, String code, String name, boolean active) {
    }

    public record CustomerSnapshot(Long id, String code, String name, Long entityId, boolean active) {
    }

    public record VendorSnapshot(Long id, String code, String name, boolean active) {
    }

    public record Stats(long entities, long customers, long vendors) {
    }

    private final RestClient client;

    public MasterDataClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.masterdata());
    }

    public EntitySnapshot entity(Long id) {
        return get(() -> client.get().uri("/internal/entities/{id}", id).retrieve().body(EntitySnapshot.class),
                "Entity", id);
    }

    public SubEntitySnapshot subEntity(Long entityId, Long subEntityId) {
        return get(() -> client.get().uri("/internal/entities/{e}/sub-entities/{s}", entityId, subEntityId)
                .retrieve().body(SubEntitySnapshot.class), "Sub-entity", subEntityId);
    }

    public CustomerSnapshot customer(Long id) {
        return get(() -> client.get().uri("/internal/customers/{id}", id).retrieve().body(CustomerSnapshot.class),
                "Customer", id);
    }

    public VendorSnapshot vendor(Long id) {
        return get(() -> client.get().uri("/internal/vendors/{id}", id).retrieve().body(VendorSnapshot.class),
                "Vendor", id);
    }

    /** The vendor, which must exist and be active to quote. */
    public VendorSnapshot activeVendor(Long id) {
        VendorSnapshot vendor = vendor(id);
        if (!vendor.active()) {
            throw new BusinessRuleException("VENDOR_INACTIVE", vendor.name() + " is inactive");
        }
        return vendor;
    }

    public Stats stats() {
        return get(() -> client.get().uri("/internal/stats").retrieve().body(Stats.class), "Statistics", "all");
    }

    private static <T> T get(Supplier<T> call, String what, Object id) {
        T result;
        try {
            result = call.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResourceNotFoundException(what, id);
            }
            throw new ServiceUnavailableException("master data service", e);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("master data service", e);
        }
        if (result == null) {
            throw new ResourceNotFoundException(what, id);
        }
        return result;
    }
}
