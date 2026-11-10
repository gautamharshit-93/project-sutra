package com.sutra.auth.service.impl;

import com.sutra.auth.service.VendorSignupService;
import static com.sutra.common.audit.AuditService.details;

import com.sutra.auth.client.MasterDataClient;
import com.sutra.auth.entity.AppUser;
import com.sutra.auth.entity.VendorSignup;
import com.sutra.auth.exception.UserNotFoundException;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.repository.RoleRepository;
import com.sutra.auth.repository.VendorSignupRepository;
import com.sutra.auth.dto.request.AuthRequests.VendorSignupRequest;
import com.sutra.auth.dto.response.UserResponses.SignupAccepted;
import com.sutra.auth.dto.response.UserResponses.UserResponse;
import com.sutra.auth.dto.response.UserResponses.VendorSignupResponse;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.security.RoleName;
import com.sutra.common.util.Text;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendors sign up themselves on the portal; an administrator approves each one with a single click. Until then the
 * login is inactive, so a stranger who signs up never sees the company's RFQs.
 */
@Service
@Transactional
public class VendorSignupServiceImpl implements VendorSignupService {

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final VendorSignupRepository signupRepository;
    private final PasswordEncoder passwordEncoder;
    private final MasterDataClient masterData;
    private final AuditService auditService;

    public VendorSignupServiceImpl(AppUserRepository userRepository, RoleRepository roleRepository,
                               VendorSignupRepository signupRepository, PasswordEncoder passwordEncoder,
                               MasterDataClient masterData, AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.signupRepository = signupRepository;
        this.passwordEncoder = passwordEncoder;
        this.masterData = masterData;
        this.auditService = auditService;
    }

    @Override
    public SignupAccepted register(VendorSignupRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new DuplicateResourceException("Username " + username + " is already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException("Email " + email + " is already registered");
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(request.contactPerson().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(false);
        user.setPendingApproval(true);
        user.setRoles(new HashSet<>(roleRepository.findByNameIn(Set.of(RoleName.VENDOR))));
        user = userRepository.save(user);

        VendorSignup signup = new VendorSignup();
        signup.setUser(user);
        signup.setCompanyName(request.companyName().trim());
        signup.setContactPerson(Text.blankToNull(request.contactPerson()));
        signup.setPhone(Text.blankToNull(request.phone()));
        signup.setTaxNumber(Text.blankToNull(request.taxNumber()));
        signup.setAddress(Text.blankToNull(request.address()));
        signup.setWebsite(Text.blankToNull(request.website()));
        signupRepository.save(signup);

        auditService.recordAs(username, "USER", user.getId(), "VENDOR_SIGNUP",
                details("company", signup.getCompanyName()));
        return new SignupAccepted(username, "Thank you. Your account for " + signup.getCompanyName()
                + " is waiting for approval by the SUTRA administrator. You can sign in as soon as it is approved.");
    }

    @Transactional(readOnly = true)
    @Override
    public List<VendorSignupResponse> pending() {
        return signupRepository.findAllByOrderByCreatedAtAsc().stream().map(VendorSignupResponse::from).toList();
    }

    /** Creates the vendor in master data, links the login to it and activates the login. */
    @Override
    public UserResponse approve(Long userId) {
        VendorSignup signup = find(userId);
        AppUser user = signup.getUser();
        MasterDataClient.VendorSnapshot vendor = masterData.registerVendor(new MasterDataClient.NewVendor(
                signup.getCompanyName(), signup.getContactPerson(), user.getEmail(), signup.getPhone(),
                signup.getAddress(), signup.getTaxNumber(), signup.getWebsite()));
        user.setVendorId(vendor.id());
        user.setVendorName(vendor.name());
        user.setActive(true);
        user.setPendingApproval(false);
        user.revokeTokens();
        signupRepository.delete(signup);
        auditService.record("USER", user.getId(), "VENDOR_APPROVED", details("username", user.getUsername(),
                "vendor", vendor.name(), "vendorCode", vendor.code()));
        return UserResponse.from(user);
    }

    /** Deletes the waiting login and its details. */
    @Override
    public void reject(Long userId) {
        VendorSignup signup = find(userId);
        AppUser user = signup.getUser();
        String company = signup.getCompanyName();
        signupRepository.delete(signup);
        userRepository.delete(user);
        auditService.record("USER", userId, "VENDOR_REJECTED", details("username", user.getUsername(),
                "company", company));
    }

    private VendorSignup find(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        return signupRepository.findByUserId(userId).orElseThrow(() ->
                new BusinessRuleException("NO_PENDING_SIGNUP", "This user has no vendor sign-up waiting for approval"));
    }
}
