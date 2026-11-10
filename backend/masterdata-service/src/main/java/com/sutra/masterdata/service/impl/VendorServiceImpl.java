package com.sutra.masterdata.service;

import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.masterdata.entity.Vendor;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.masterdata.exception.VendorNotFoundException;
import com.sutra.masterdata.client.UsageClient;
import com.sutra.masterdata.repository.VendorRepository;
import com.sutra.masterdata.request.MasterDataRequests.VendorRequest;
import com.sutra.masterdata.request.MasterDataRequests.VendorProfileRequest;
import com.sutra.masterdata.request.MasterDataRequests.VendorSignupRequest;
import com.sutra.masterdata.response.MasterDataResponses.VendorResponse;
import com.sutra.common.web.PageResponse;
import com.sutra.common.util.Text;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VendorService {

    static final String AUDIT_TYPE = "VENDOR";

    private final VendorRepository vendorRepository;
    private final UsageClient usage;
    private final AuditService auditService;

    public VendorService(VendorRepository vendorRepository, UsageClient usage,
                         AuditService auditService) {
        this.vendorRepository = vendorRepository;
        this.usage = usage;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<VendorResponse> search(String q, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Vendor::getName));
        return PageResponse.of(vendorRepository.search(Text.blankToNull(q), pageable), VendorResponse::from);
    }

    @Transactional(readOnly = true)
    public VendorResponse get(Long id) {
        return VendorResponse.from(find(id));
    }

    public VendorResponse create(VendorRequest request) {
        String code = BusinessEntityService.normaliseCode(request.code());
        if (vendorRepository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("A vendor with code " + code + " already exists");
        }
        Vendor vendor = new Vendor();
        apply(vendor, request, code);
        vendor = vendorRepository.save(vendor);
        auditService.record(AUDIT_TYPE, vendor.getId(), "CREATED", details("code", code, "name", vendor.getName()));
        return VendorResponse.from(vendor);
    }

    /** A vendor approved after signing up on the portal; its code is made from the name. */
    public VendorResponse register(VendorSignupRequest request) {
        String code = BusinessEntityService.codeFromName(request.name(), vendorRepository::existsByCodeIgnoreCase);
        Vendor vendor = new Vendor();
        apply(vendor, new VendorRequest(code, request.name(), request.contactPerson(), request.email(), request.phone(),
                request.address(), request.taxNumber(), request.website(), true), code);
        vendor = vendorRepository.save(vendor);
        auditService.record(AUDIT_TYPE, vendor.getId(), "CREATED", details("code", code, "name", vendor.getName(),
                "via", "vendor sign-up"));
        return VendorResponse.from(vendor);
    }

    /** A vendor updating its own contact and tax details on the portal. */
    public VendorResponse updateProfile(Long id, VendorProfileRequest request) {
        Vendor vendor = find(id);
        vendor.setContactPerson(Text.blankToNull(request.contactPerson()));
        vendor.setEmail(Text.blankToNull(request.email()));
        vendor.setPhone(Text.blankToNull(request.phone()));
        vendor.setAddress(Text.blankToNull(request.address()));
        vendor.setTaxNumber(Text.blankToNull(request.taxNumber()));
        vendor.setWebsite(Text.blankToNull(request.website()));
        auditService.record(AUDIT_TYPE, id, "PROFILE_UPDATED", details("by", "vendor"));
        return VendorResponse.from(vendor);
    }

    public VendorResponse update(Long id, VendorRequest request) {
        Vendor vendor = find(id);
        String code = BusinessEntityService.normaliseCode(request.code());
        if (vendorRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new DuplicateResourceException("A vendor with code " + code + " already exists");
        }
        apply(vendor, request, code);
        auditService.record(AUDIT_TYPE, id, "UPDATED", details("code", code, "active", vendor.isActive()));
        return VendorResponse.from(vendor);
    }

    public void delete(Long id) {
        Vendor vendor = find(id);
        if (usage.procurementUsage("vendors", id).inUse()) {
            throw new BusinessRuleException("VENDOR_IN_USE",
                    vendor.getName() + " has quotations on record; deactivate the vendor instead");
        }
        if (usage.vendorLogins(id) > 0) {
            throw new BusinessRuleException("VENDOR_HAS_LOGINS",
                    vendor.getName() + " still has user logins; delete or relink them first, or deactivate the vendor");
        }
        vendorRepository.delete(vendor);
        auditService.record(AUDIT_TYPE, id, "DELETED", details("code", vendor.getCode()));
    }

    public Vendor find(Long id) {
        return vendorRepository.findById(id).orElseThrow(() -> new VendorNotFoundException(id));
    }

    /** A vendor that can receive new quotations. */
    public Vendor findActive(Long id) {
        Vendor vendor = find(id);
        if (!vendor.isActive()) {
            throw new BusinessRuleException("VENDOR_INACTIVE", vendor.getName() + " is inactive");
        }
        return vendor;
    }

    private static void apply(Vendor vendor, VendorRequest request, String code) {
        vendor.setCode(code);
        vendor.setName(request.name().trim());
        vendor.setContactPerson(Text.blankToNull(request.contactPerson()));
        vendor.setEmail(Text.blankToNull(request.email()));
        vendor.setPhone(Text.blankToNull(request.phone()));
        vendor.setAddress(Text.blankToNull(request.address()));
        vendor.setTaxNumber(Text.blankToNull(request.taxNumber()));
        vendor.setWebsite(Text.blankToNull(request.website()));
        vendor.setActive(request.active() == null || request.active());
    }
}
