package com.sutra.common.security;

import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated caller. Built from a verified JWT in every service (no password), or from the user table by
 * the auth service at sign-in. Authorities are {@code ROLE_<name>}.
 */
public class SutraUserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final boolean active;
    private final Set<RoleName> roles;
    private final Long vendorId;
    private final long tokenVersion;

    public SutraUserPrincipal(Long id, String username, String passwordHash, boolean active, Set<RoleName> roles) {
        this(id, username, passwordHash, active, roles, null, 0);
    }

    public SutraUserPrincipal(Long id, String username, String passwordHash, boolean active, Set<RoleName> roles,
                              Long vendorId, long tokenVersion) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.active = active;
        this.roles = Set.copyOf(roles);
        this.vendorId = vendorId;
        this.tokenVersion = tokenVersion;
    }

    /** A caller that is another SUTRA service, e.g. procurement asking master data for a vendor. */
    public static SutraUserPrincipal service(String serviceName) {
        return new SutraUserPrincipal(null, serviceName, null, true, Set.of(RoleName.SERVICE));
    }

    public Long getId() {
        return id;
    }

    public Set<RoleName> getRoles() {
        return roles;
    }

    public boolean hasRole(RoleName role) {
        return roles.contains(role);
    }

    /** The vendor a VENDOR user acts for; null for company users. */
    public Long getVendorId() {
        return vendorId;
    }

    /** Increases whenever the user's roles, status or password change; older tokens are then refused. */
    public long getTokenVersion() {
        return tokenVersion;
    }

    public boolean isService() {
        return roles.contains(RoleName.SERVICE);
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
