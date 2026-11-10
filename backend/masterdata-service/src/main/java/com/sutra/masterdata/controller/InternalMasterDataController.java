package com.sutra.masterdata.controller;

import com.sutra.masterdata.entity.BusinessEntity;
import com.sutra.masterdata.entity.Customer;
import com.sutra.masterdata.entity.SubEntity;
import com.sutra.masterdata.entity.Vendor;
import com.sutra.masterdata.repository.BusinessEntityRepository;
import com.sutra.masterdata.repository.CustomerRepository;
import com.sutra.masterdata.repository.VendorRepository;
import com.sutra.masterdata.service.BusinessEntityService;
import com.sutra.masterdata.service.CustomerService;
import com.sutra.masterdata.service.VendorService;
import org.springframework.transaction.annotation.Transactional;
import com.sutra.masterdata.dto.request.MasterDataRequests.VendorSignupRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API (service tokens only; the gateway never forwards {@code /internal}). Other services read
 * the few master-data fields they keep alongside their own records. Unknown ids answer 404.
 */
@RestController
@RequestMapping("/internal")
@Transactional(readOnly = true)
public class InternalMasterDataController {

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

    private final BusinessEntityService entityService;
    private final CustomerService customerService;
    private final VendorService vendorService;
    private final BusinessEntityRepository entityRepository;
    private final CustomerRepository customerRepository;
    private final VendorRepository vendorRepository;

    public InternalMasterDataController(BusinessEntityService entityService, CustomerService customerService,
                                        VendorService vendorService, BusinessEntityRepository entityRepository,
                                        CustomerRepository customerRepository, VendorRepository vendorRepository) {
        this.entityService = entityService;
        this.customerService = customerService;
        this.vendorService = vendorService;
        this.entityRepository = entityRepository;
        this.customerRepository = customerRepository;
        this.vendorRepository = vendorRepository;
    }

    @GetMapping("/entities/{id}")
    public EntitySnapshot entity(@PathVariable Long id) {
        BusinessEntity e = entityService.find(id);
        return new EntitySnapshot(e.getId(), e.getCode(), e.getName(), e.isActive());
    }

    @GetMapping("/entities/{entityId}/sub-entities/{subId}")
    public SubEntitySnapshot subEntity(@PathVariable Long entityId, @PathVariable Long subId) {
        SubEntity s = entityService.findSub(entityId, subId);
        return new SubEntitySnapshot(s.getId(), s.getEntity().getId(), s.getCode(), s.getName(), s.isActive());
    }

    @GetMapping("/customers/{id}")
    public CustomerSnapshot customer(@PathVariable Long id) {
        Customer c = customerService.find(id);
        return new CustomerSnapshot(c.getId(), c.getCode(), c.getName(),
                c.getEntity() == null ? null : c.getEntity().getId(), c.isActive());
    }

    @GetMapping("/vendors/{id}")
    public VendorSnapshot vendor(@PathVariable Long id) {
        Vendor v = vendorService.find(id);
        return new VendorSnapshot(v.getId(), v.getCode(), v.getName(), v.isActive());
    }

    /** The auth service creates the vendor when an administrator approves a portal sign-up. */
    @PostMapping("/vendors")
    @Transactional
    public VendorSnapshot registerVendor(@Valid @RequestBody VendorSignupRequest request) {
        var v = vendorService.register(request);
        return new VendorSnapshot(v.id(), v.code(), v.name(), v.active());
    }

    @GetMapping("/stats")
    public Stats stats() {
        return new Stats(entityRepository.count(), customerRepository.count(), vendorRepository.count());
    }
}
