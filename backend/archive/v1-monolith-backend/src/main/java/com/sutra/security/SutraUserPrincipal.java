package com.sutra.security;

import com.sutra.entity.AppUser;
import com.sutra.entity.enums.RoleName;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Authenticated user as seen by Spring Security. Authorities are {@code ROLE_<name>}. */
public class SutraUserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final boolean active;
    private final Set<RoleName> roles;
    private final Long vendorId;

    public SutraUserPrincipal(Long id, String username, String passwordHash, boolean active, Set<RoleName> roles) {
        this(id, username, passwordHash, active, roles, null);
    }

    public SutraUserPrincipal(Long id, String username, String passwordHash, boolean active, Set<RoleName> roles,
                              Long vendorId) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.active = active;
        this.roles = Set.copyOf(roles);
        this.vendorId = vendorId;
    }

    public static SutraUserPrincipal from(AppUser user) {
        Set<RoleName> roles = user.getRoles().stream().map(r -> r.getName()).collect(Collectors.toSet());
        return new SutraUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash(), user.isActive(), roles,
                user.getVendor() == null ? null : user.getVendor().getId());
    }

    public Long getId() {
        return id;
    }

    /** The vendor a VENDOR user acts for; null for company users. */
    public Long getVendorId() {
        return vendorId;
    }

    public Set<RoleName> getRoles() {
        return roles;
    }

    public boolean hasRole(RoleName role) {
        return roles.contains(role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
