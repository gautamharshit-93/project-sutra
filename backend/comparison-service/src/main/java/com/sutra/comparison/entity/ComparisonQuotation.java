package com.sutra.comparison.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Records which quotation, at which data version, took part in a comparison report. */
@Entity
@Table(name = "comparison_quotation")
@Getter
@Setter
public class ComparisonQuotation {

    @EmbeddedId
    private Key id = new Key();

    @MapsId("comparisonReportId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comparison_report_id")
    private ComparisonReport comparisonReport;

    /** The quotation compared (owned by the quotation service; a cross-schema foreign key keeps it valid). */
    public Long getQuotationId() {
        return id.getQuotationId();
    }

    public void setQuotationId(Long quotationId) {
        id.setQuotationId(quotationId);
    }

    @Column(name = "quotation_version", nullable = false)
    private Long quotationVersion;

    @Column(name = "grand_total", precision = 18, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "items_quoted", nullable = false)
    private int itemsQuoted;

    @Column(name = "items_missing", nullable = false)
    private int itemsMissing;

    @Column(name = "price_rank")
    private Integer priceRank;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        @Column(name = "comparison_report_id")
        private Long comparisonReportId;

        @Column(name = "quotation_id")
        private Long quotationId;
    }
}
