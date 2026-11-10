package com.sutra.masterdata.entity;

import com.sutra.masterdata.entity.base.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** A parent business entity (company / organisation unit). Named to avoid clashing with JPA's @Entity. */
@Entity
@Table(name = "business_entity")
@Getter
@Setter
public class BusinessEntity extends AuditableEntity {

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String address;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL)
    @OrderBy("code")
    private List<SubEntity> subEntities = new ArrayList<>();
}
