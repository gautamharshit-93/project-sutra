package com.sutra.auth.dto.response;

import com.sutra.auth.entity.AppUser;
import com.sutra.common.security.RoleName;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

public final class UserResponses {

    private UserResponses() {
    }

    public record UserResponse(Long id, String username, String email, String fullName, boolean active,
                               Set<RoleName> roles, Long vendorId, String vendorName, Instant createdAt,
                               boolean pendingApproval, boolean passwordChangeRequired) {
        public static UserResponse from(AppUser u) {
            Set<RoleName> roles = new TreeSet<>();
            u.getRoles().forEach(r -> roles.add(r.getName()));
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.isActive(),
                    roles, u.getVendorId(), u.getVendorName(), u.getCreatedAt(), u.isPendingApproval(),
                    u.isPasswordChangeRequired());
        }
    }

    public record AuthResponse(String token, Instant expiresAt, UserResponse user) {
    }

    public record RoleResponse(RoleName name, String description) {
    }

    /** A vendor sign-up waiting for approval, with the company details the vendor entered. */
    public record VendorSignupResponse(Long userId, String username, String email, String companyName,
                                       String contactPerson, String phone, String taxNumber, String address,
                                       String website, Instant createdAt) {
        public static VendorSignupResponse from(com.sutra.auth.entity.VendorSignup s) {
            return new VendorSignupResponse(s.getUser().getId(), s.getUser().getUsername(), s.getUser().getEmail(),
                    s.getCompanyName(), s.getContactPerson(), s.getPhone(), s.getTaxNumber(), s.getAddress(),
                    s.getWebsite(), s.getCreatedAt());
        }
    }

    /** What a vendor sees after signing up. */
    public record SignupAccepted(String username, String message) {
    }
}
