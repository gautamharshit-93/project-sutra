package com.sutra.masterdata.entity;

import com.sutra.masterdata.entity.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "vendor")
@Getter
@Setter
public class Vendor extends AuditableEntity {

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    private String email;

    @Column(length = 40)
    private String phone;

    @Column(columnDefinition = "text")
    private String address;

    @Column(name = "tax_number", length = 50)
    private String taxNumber;

    private String website;

    @Column(nullable = false)
    private boolean active = true;
}
