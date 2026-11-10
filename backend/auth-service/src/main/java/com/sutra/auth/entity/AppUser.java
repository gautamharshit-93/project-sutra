package com.sutra.auth.entity;

import com.sutra.auth.entity.base.AuditableEntity;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser extends AuditableEntity {

    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean active = true;

    /** A vendor that signed up on the portal and is waiting for an administrator's approval (inactive until then). */
    @Column(name = "pending_approval", nullable = false)
    private boolean pendingApproval;

    /** Still on a known start-up password: the app makes the user choose a new one first. */
    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /** The supplier a VENDOR user acts for (a master data vendor id); null for the company's own users. */
    @Column(name = "vendor_id")
    private Long vendorId;

    /** The vendor's name when the link was made, so users can be listed without calling master data. */
    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    /** Raised on every change to roles, status or password; the gateway then refuses older tokens. */
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    public void revokeTokens() {
        tokenVersion++;
    }

    /** The user as Spring Security sees it at sign-in. */
    public SutraUserPrincipal toPrincipal() {
        Set<RoleName> names = new HashSet<>();
        roles.forEach(r -> names.add(r.getName()));
        return new SutraUserPrincipal(getId(), username, passwordHash, active, names, vendorId, tokenVersion);
    }

    public boolean hasRole(RoleName roleName) {
        return roles.stream().anyMatch(r -> r.getName() == roleName);
    }
}
