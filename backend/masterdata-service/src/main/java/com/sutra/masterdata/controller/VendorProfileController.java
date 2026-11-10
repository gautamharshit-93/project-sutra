package com.sutra.masterdata.controller;

import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.Roles;
import com.sutra.masterdata.dto.request.MasterDataRequests.VendorProfileRequest;
import com.sutra.masterdata.dto.response.MasterDataResponses.VendorResponse;
import com.sutra.masterdata.service.VendorService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A vendor's own company profile on the vendor portal. The vendor comes from the login, never from the request. */
@RestController
@PreAuthorize(Roles.VENDOR)
@RequestMapping("/api/vendor-profile")
public class VendorProfileController {

    private final VendorService service;
    private final CurrentUserProvider currentUser;

    public VendorProfileController(VendorService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public VendorResponse get() {
        return service.get(vendorId());
    }

    @PutMapping
    public VendorResponse update(@Valid @RequestBody VendorProfileRequest request) {
        return service.updateProfile(vendorId(), request);
    }

    private Long vendorId() {
        Long id = currentUser.requirePrincipal().getVendorId();
        if (id == null) {
            throw new BusinessRuleException("VENDOR_REQUIRED", "This login is not linked to a vendor");
        }
        return id;
    }
}
