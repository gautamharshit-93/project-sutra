package com.sutra.quotation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * A record owned by another service (an entity, sub-entity, customer or vendor in master data), kept as its id and
 * its name at the time it was used. There is no foreign key across services; the name keeps old records readable
 * even if master data changes later.
 */
@Embeddable
public class NamedRef {

    @Column(name = "id")
    private Long id;

    @Column(name = "name")
    private String name;

    protected NamedRef() {
    }

    public NamedRef(Long id, String name) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NamedRef other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return name + " (#" + id + ")";
    }
}
