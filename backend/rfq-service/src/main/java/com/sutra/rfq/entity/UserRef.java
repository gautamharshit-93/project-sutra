package com.sutra.rfq.entity;

import com.sutra.common.security.SutraUserPrincipal;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/** A user of the auth service, kept as id and username (no foreign key across services). */
@Embeddable
public class UserRef {

    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String username;

    protected UserRef() {
    }

    public UserRef(Long id, String username) {
        this.id = Objects.requireNonNull(id);
        this.username = Objects.requireNonNull(username);
    }

    public static UserRef of(SutraUserPrincipal principal) {
        return new UserRef(principal.getId(), principal.getUsername());
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UserRef other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return username;
    }
}
