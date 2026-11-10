package com.sutra.auth.entity;

import com.sutra.auth.entity.base.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** The company details a vendor gave when signing up, kept until an administrator approves or rejects it. */
@Entity
@Table(name = "vendor_signup")
@Getter
@Setter
public class VendorSignup extends AuditableEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(length = 40)
    private String phone;

    @Column(length = 1000)
    private String address;

    @Column(name = "tax_number", length = 50)
    private String taxNumber;

    @Column(length = 255)
    private String website;
}
