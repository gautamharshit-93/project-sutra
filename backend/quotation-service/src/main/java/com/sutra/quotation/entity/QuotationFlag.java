package com.sutra.quotation.entity;

import com.sutra.quotation.entity.base.BaseEntity;
import com.sutra.api.enums.FlagSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An uncertain, conflicting or missing value that needs human review. */
@Entity
@Table(name = "quotation_flag")
@Getter
@Setter
@NoArgsConstructor
public class QuotationFlag extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quotation_item_id")
    private QuotationItem quotationItem;

    @Column(name = "field_name", nullable = false, length = 60)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FlagSeverity severity;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(nullable = false)
    private boolean resolved;

    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "resolved_by_id"))
    @AttributeOverride(name = "username", column = @Column(name = "resolved_by_name"))
    private UserRef resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public QuotationFlag(QuotationItem item, String fieldName, FlagSeverity severity, String code, String message) {
        this.quotationItem = item;
        this.fieldName = fieldName;
        this.severity = severity;
        this.code = code;
        this.message = message.length() > 500 ? message.substring(0, 497) + "..." : message;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void resolve(UserRef user) {
        resolved = true;
        resolvedBy = user;
        resolvedAt = Instant.now();
    }
}
