package com.sutra.auth.service.impl;

import com.sutra.auth.service.UserService;
import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.auth.entity.AppUser;
import com.sutra.auth.entity.Role;
import com.sutra.auth.client.MasterDataClient;
import com.sutra.auth.client.ProcurementClient;
import com.sutra.common.security.RoleName;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.auth.exception.UserNotFoundException;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.repository.RoleRepository;
import com.sutra.auth.dto.request.UserRequest;
import com.sutra.common.web.PageResponse;
import com.sutra.auth.dto.response.UserResponses.RoleResponse;
import com.sutra.auth.dto.response.UserResponses.UserResponse;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.auth.service.internal.LoginAttemptService;
import com.sutra.common.util.Text;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User accounts and role assignment (administrators only). */
@Service
@Transactional
public class UserServiceImpl implements UserService {

    static final String AUDIT_TYPE = "USER";

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final LoginAttemptService loginAttempts;
    private final MasterDataClient masterData;
    private final ProcurementClient procurement;

    public UserServiceImpl(AppUserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, CurrentUserProvider currentUser,
                       AuditService auditService, LoginAttemptService loginAttempts, MasterDataClient masterData,
                       ProcurementClient procurement) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.loginAttempts = loginAttempts;
        this.masterData = masterData;
        this.procurement = procurement;
    }

    @Transactional(readOnly = true)
    @Override
    public PageResponse<UserResponse> search(String q, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(AppUser::getUsername));
        return PageResponse.of(userRepository.search(Text.blankToNull(q), pageable), UserResponse::from);
    }

    @Transactional(readOnly = true)
    @Override
    public UserResponse get(Long id) {
        return UserResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    @Override
    public List<RoleResponse> roles() {
        return roleRepository.findAll(Sort.by(Role::getId)).stream()
                .map(r -> new RoleResponse(r.getName(), r.getDescription())).toList();
    }

    @Override
    public UserResponse create(UserRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new DuplicateResourceException("Username " + username + " is already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(request.email().trim())) {
            throw new DuplicateResourceException("Email " + request.email() + " is already registered");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new BusinessRuleException("PASSWORD_REQUIRED", "A password is required for a new user");
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(request.active() == null || request.active());
        user.setRoles(resolveRoles(request.roles()));
        linkVendor(user, request);
        user = userRepository.save(user);
        auditService.record(AUDIT_TYPE, user.getId(), "CREATED", details("username", username, "roles", request.roles(),
                "vendor", user.getVendorName()));
        return UserResponse.from(user);
    }

    @Override
    public UserResponse update(Long id, UserRequest request) {
        AppUser user = find(id);
        if (user.isPendingApproval()) {
            throw new BusinessRuleException("SIGNUP_PENDING",
                    user.getUsername() + " is a vendor sign-up waiting for approval: approve or reject it instead");
        }
        if (!user.getUsername().equalsIgnoreCase(request.username().trim())) {
            throw new BusinessRuleException("USERNAME_IMMUTABLE", "Usernames cannot be changed");
        }
        if (userRepository.existsByEmailIgnoreCaseAndIdNot(request.email().trim(), id)) {
            throw new DuplicateResourceException("Email " + request.email() + " is already registered");
        }
        boolean self = id.equals(currentUser.requirePrincipal().getId());
        boolean active = request.active() == null || request.active();
        if (self && (!active || !request.roles().contains(RoleName.ADMIN))) {
            throw new BusinessRuleException("SELF_LOCKOUT", "You cannot deactivate yourself or remove your own ADMIN role");
        }
        user.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        user.setFullName(request.fullName().trim());
        user.setActive(active);
        user.setRoles(resolveRoles(request.roles()));
        linkVendor(user, request);
        boolean passwordChanged = request.password() != null && !request.password().isBlank();
        if (passwordChanged) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            loginAttempts.unlock(user.getUsername()); // an administrator reset also lifts a sign-in lockout
        }
        user.revokeTokens(); // the user signs in again with the new roles, status or password
        auditService.record(AUDIT_TYPE, id, "UPDATED", details("roles", request.roles(), "active", active,
                "passwordReset", passwordChanged));
        return UserResponse.from(user);
    }

    @Override
    public void delete(Long id) {
        AppUser user = find(id);
        if (id.equals(currentUser.requirePrincipal().getId())) {
            throw new BusinessRuleException("SELF_DELETE", "You cannot delete your own account");
        }
        if (procurement.userUsage(id).inUse()) {
            throw new BusinessRuleException("USER_IN_USE",
                    user.getUsername() + " owns purchase requests or quotations; deactivate the account instead");
        }
        userRepository.delete(user);
        auditService.record(AUDIT_TYPE, id, "DELETED", details("username", user.getUsername()));
    }

    @Override
    public AppUser find(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * A VENDOR user logs in for exactly one active vendor and has no company role, so it can never see other
     * vendors' quotations; company users are never linked to a vendor.
     */
    private void linkVendor(AppUser user, UserRequest request) {
        if (!request.roles().contains(RoleName.VENDOR)) {
            if (request.vendorId() != null) {
                throw new BusinessRuleException("VENDOR_ROLE_REQUIRED", "Only a user with the VENDOR role can be linked to a vendor");
            }
            user.setVendorId(null);
            user.setVendorName(null);
            return;
        }
        if (request.roles().size() > 1) {
            throw new BusinessRuleException("VENDOR_ROLE_EXCLUSIVE",
                    "A vendor login cannot also have a company role (admin, buyer, manager or auditor)");
        }
        if (request.vendorId() == null) {
            throw new BusinessRuleException("VENDOR_REQUIRED", "Choose the vendor this login belongs to");
        }
        if (request.vendorId().equals(user.getVendorId())) {
            return; // an unchanged link stays valid even if the vendor was deactivated meanwhile
        }
        MasterDataClient.VendorSnapshot vendor = masterData.activeVendor(request.vendorId());
        user.setVendorId(vendor.id());
        user.setVendorName(vendor.name());
    }

    private Set<Role> resolveRoles(Set<RoleName> names) {
        List<Role> roles = roleRepository.findByNameIn(names);
        if (roles.size() != names.size()) {
            throw new BusinessRuleException("UNKNOWN_ROLE", "One or more roles do not exist");
        }
        return new HashSet<>(roles);
    }
}
