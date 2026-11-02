package com.sutra.auth.controller;

import com.sutra.auth.repository.AppUserRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets master data check that no login is linked to a vendor before deleting it (service tokens only). */
@RestController
@RequestMapping("/internal/vendors")
public class InternalVendorLoginController {

    public record VendorLogins(long users) {
    }

    private final AppUserRepository userRepository;

    public InternalVendorLoginController(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/{vendorId}/logins")
    @Transactional(readOnly = true)
    public VendorLogins logins(@PathVariable Long vendorId) {
        return new VendorLogins(userRepository.countByVendorId(vendorId));
    }
}
