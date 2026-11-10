package com.sutra.masterdata.entity;

import com.sutra.masterdata.entity.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "customer")
@Getter
@Setter
public class Customer extends AuditableEntity {

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id")
    private BusinessEntity entity;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    private String email;

    @Column(length = 40)
    private String phone;

    @Column(columnDefinition = "text")
    private String address;

    @Column(nullable = false)
    private boolean active = true;
}
