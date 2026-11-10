package com.sutra.comparison.support;

import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FlagSeverity;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.quotation.QuotationResponses.QuotationFlagResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationItemResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A quotation as the quotation service returns it ({@link #toResponse()}), already read and recalculated: each line
 * total is quantity x unit price plus tax (no discounts in these tests), as the quotation service computes it.
 */
@Getter
@Setter
public class TestQuotation {

    private Long id;
    private Long purchaseRequestId;
    private Long vendorId;
    private String vendorName;
    private String currency = "INR";
    private QuotationStatus status = QuotationStatus.READY_FOR_COMPARISON;
    private LocalDate validUntil;
    private String paymentTerms = "30 days";
    private String deliveryTerms = "2 weeks";
    private String warrantyTerms;
    private long version;
    private int openFlags;
    private final List<Line> lines = new ArrayList<>();

    /** One quoted line. */
    public record Line(Long id, int lineNumber, Long requestItemId, Integer requestLineNumber, String description,
                       BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxPercent) {

        BigDecimal total() {
            if (quantity == null || unitPrice == null) {
                return null;
            }
            BigDecimal base = quantity.multiply(unitPrice);
            BigDecimal tax = taxPercent == null ? BigDecimal.ZERO
                    : base.multiply(taxPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            return base.add(tax).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public void addOpenFlag() {
        openFlags++;
    }

    public QuotationResponse toResponse() {
        List<QuotationItemResponse> items = lines.stream().map(l -> new QuotationItemResponse(l.id(), l.lineNumber(),
                l.requestItemId(), l.requestLineNumber(), null, l.description(), l.quantity(), "Nos", l.unitPrice(),
                null, null, l.taxPercent(), null, l.total(), null,
                l.requestItemId() == null ? null : new BigDecimal("0.950"), new BigDecimal("0.950"), false, null)).toList();
        BigDecimal grand = lines.stream().map(Line::total).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<QuotationFlagResponse> flags = new ArrayList<>();
        for (int i = 0; i < openFlags; i++) {
            flags.add(new QuotationFlagResponse((long) i + 1, null, "items", FlagSeverity.WARNING, "EXTRACTOR_NOTE",
                    "check", false, null, null, Instant.now()));
        }
        return new QuotationResponse(id, purchaseRequestId, "PR-2026-00001", vendorId, vendorName, "Q-" + id, null,
                validUntil, currency, status, grand, BigDecimal.ZERO, null, grand, null, null, null, deliveryTerms,
                paymentTerms, warrantyTerms, null, ExtractionSource.GROQ, new BigDecimal("0.950"), null, 1, null,
                "buyer", false, version, true, List.of(), items, flags, Instant.now(), Instant.now());
    }
}
