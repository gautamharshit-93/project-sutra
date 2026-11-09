package com.sutra.auth.client;

import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceUrls;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.error.ServiceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Looks up the vendor a VENDOR login is linked to, in the master data service. */
@Component
public class MasterDataClient {

    /** What the auth service needs to know about a vendor. */
    public record VendorSnapshot(Long id, String code, String name, boolean active) {
    }

    /** The vendor to create when a portal sign-up is approved. */
    public record NewVendor(String name, String contactPerson, String email, String phone, String address,
                            String taxNumber, String website) {
    }

    private final RestClient client;

    public MasterDataClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.masterdata());
    }

    /** Creates the vendor of an approved sign-up (the master data service makes its code). */
    public VendorSnapshot registerVendor(NewVendor vendor) {
        try {
            VendorSnapshot created = client.post().uri("/internal/vendors").body(vendor).retrieve().body(VendorSnapshot.class);
            if (created == null) {
                throw new ServiceUnavailableException("master data service", null);
            }
            return created;
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("master data service", e);
        }
    }

    /** The vendor, which must exist and be active. */
    public VendorSnapshot activeVendor(Long vendorId) {
        VendorSnapshot vendor;
        try {
            vendor = client.get().uri("/internal/vendors/{id}", vendorId).retrieve().body(VendorSnapshot.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResourceNotFoundException("Vendor", vendorId);
            }
            throw new ServiceUnavailableException("master data service", e);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("master data service", e);
        }
        if (vendor == null || !vendor.active()) {
            throw new BusinessRuleException("VENDOR_INACTIVE", (vendor == null ? "The vendor" : vendor.name()) + " is inactive");
        }
        return vendor;
    }
}
