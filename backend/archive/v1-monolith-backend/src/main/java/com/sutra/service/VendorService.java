package com.sutra.service;

import static com.sutra.service.AuditService.details;

import com.sutra.entity.Vendor;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.DuplicateResourceException;
import com.sutra.exception.VendorNotFoundException;
import com.sutra.repository.QuotationRepository;
import com.sutra.repository.VendorRepository;
import com.sutra.request.MasterDataRequests.VendorRequest;
import com.sutra.response.MasterDataResponses.VendorResponse;
import com.sutra.response.PageResponse;
import com.sutra.util.Text;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VendorService {

    static final String AUDIT_TYPE = "VENDOR";

    private final VendorRepository vendorRepository;
    private final QuotationRepository quotationRepository;
    private final AuditService auditService;

    public VendorService(VendorRepository vendorRepository, QuotationRepository quotationRepository,
                         AuditService auditService) {
        this.vendorRepository = vendorRepository;
        this.quotationRepository = quotationRepository;
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
        if (quotationRepository.countByVendorId(id) > 0) {
            throw new BusinessRuleException("VENDOR_IN_USE",
                    vendor.getName() + " has quotations on record; deactivate the vendor instead");
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
