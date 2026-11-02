package com.sutra.auth.response;

import com.sutra.auth.entity.AppUser;
import com.sutra.common.security.RoleName;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

public final class UserResponses {

    private UserResponses() {
    }

    public record UserResponse(Long id, String username, String email, String fullName, boolean active,
                               Set<RoleName> roles, Long vendorId, String vendorName, Instant createdAt) {
        public static UserResponse from(AppUser u) {
            Set<RoleName> roles = new TreeSet<>();
            u.getRoles().forEach(r -> roles.add(r.getName()));
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.isActive(),
                    roles, u.getVendorId(), u.getVendorName(), u.getCreatedAt());
        }
    }

    public record AuthResponse(String token, Instant expiresAt, UserResponse user) {
    }

    public record RoleResponse(RoleName name, String description) {
    }
}
