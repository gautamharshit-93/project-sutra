package com.sutra.quotation.service.internal;

import com.sutra.api.rules.FlagCodes;
import static com.sutra.common.util.Money.approximatelyEqual;
import static com.sutra.common.util.Money.percentOf;
import static com.sutra.common.util.Money.round;

import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Authoritative arithmetic for quotations. AI output is never trusted for totals: every line and
 * quotation total is recomputed here, and any disagreement with the document's printed figures is flagged.
 *
 * <pre>
 * base      = quantity x unitPrice
 * discount  = discountAmount, else base x discountPercent / 100
 * taxable   = base - discount
 * tax       = taxAmount, else taxable x taxPercent / 100
 * lineTotal = taxable + tax
 * </pre>
 */
@Component
public class QuotationCalculator {

    /** Recalculates every line and the quotation totals in place, and returns arithmetic flags. */
    public List<FlagDraft> recalculate(Quotation quotation) {
        List<FlagDraft> flags = new ArrayList<>();
        applyDocumentLevelTax(quotation, flags);
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        boolean complete = true;

        for (QuotationItem item : quotation.getItems()) {
            LineAmounts amounts = calculateLine(item, flags);
            if (amounts == null) {
                complete = false;
                item.setLineTotal(null);
                continue;
            }
            item.setLineTotal(amounts.total());
            subtotal = subtotal.add(amounts.taxable());
            discountTotal = discountTotal.add(amounts.discount());
            taxTotal = taxTotal.add(amounts.tax());
        }

        if (quotation.getItems().isEmpty() || !complete) {
            // Partial totals would be misleading, so totals stay empty until every line is priced.
            quotation.setSubtotal(null);
            quotation.setDiscountTotal(null);
            quotation.setTaxTotal(null);
            quotation.setGrandTotal(null);
            return flags;
        }

        quotation.setSubtotal(round(subtotal));
        quotation.setDiscountTotal(round(discountTotal));
        quotation.setTaxTotal(round(taxTotal));
        quotation.setGrandTotal(round(subtotal.add(taxTotal)));

        // Vendors print the subtotal before discount (gross), after discount (taxable value) or with tax.
        BigDecimal grossBeforeDiscount = round(subtotal.add(discountTotal));
        compareStated(flags, "statedSubtotal", FlagCodes.SUBTOTAL_MISMATCH, "Subtotal",
                quotation.getStatedSubtotal(), quotation.getSubtotal(), grossBeforeDiscount, quotation.getGrandTotal());
        compareStated(flags, "statedTaxTotal", FlagCodes.TAX_TOTAL_MISMATCH, "Tax total",
                quotation.getStatedTaxTotal(), quotation.getTaxTotal());
        compareStated(flags, "statedGrandTotal", FlagCodes.GRAND_TOTAL_MISMATCH, "Grand total",
                quotation.getStatedGrandTotal(), quotation.getGrandTotal());
        return flags;
    }

    /**
     * Many quotations print tax once ("GST 18%") below the subtotal instead of per line. Left alone, such a
     * vendor's lines would look cheaper than tax-inclusive lines from other vendors. When no line carries tax but
     * the document states a tax total, the implied rate (stated tax / taxable subtotal) is applied to every line
     * and an INFO flag records it.
     */
    void applyDocumentLevelTax(Quotation quotation, List<FlagDraft> flags) {
        BigDecimal statedTax = quotation.getStatedTaxTotal();
        if (statedTax == null || statedTax.signum() <= 0 || quotation.getItems().isEmpty()) {
            return;
        }
        boolean anyLineTax = quotation.getItems().stream()
                .anyMatch(i -> i.getTaxPercent() != null || i.getTaxAmount() != null);
        boolean allPriced = quotation.getItems().stream()
                .allMatch(i -> i.getQuantity() != null && i.getUnitPrice() != null);
        if (anyLineTax || !allPriced) {
            return;
        }
        BigDecimal taxable = BigDecimal.ZERO;
        for (QuotationItem item : quotation.getItems()) {
            BigDecimal base = item.getQuantity().multiply(item.getUnitPrice());
            BigDecimal discount = item.getDiscountAmount() != null ? item.getDiscountAmount()
                    : item.getDiscountPercent() != null ? percentOf(base, item.getDiscountPercent()) : BigDecimal.ZERO;
            taxable = taxable.add(base.subtract(discount));
        }
        if (taxable.signum() <= 0) {
            return;
        }
        BigDecimal rate = statedTax.multiply(BigDecimal.valueOf(100)).divide(taxable, 4, java.math.RoundingMode.HALF_UP);
        if (rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            return;
        }
        String note = "Tax rate derived from the document's tax total";
        quotation.getItems().forEach(i -> {
            i.setTaxPercent(rate);
            i.setNotes(i.getNotes() == null ? note : i.getNotes() + "; " + note);
        });
        flags.add(FlagDraft.info(null, "taxPercent", FlagCodes.DOCUMENT_LEVEL_TAX,
                "Tax is stated only as a document total; the implied rate of " + rate.stripTrailingZeros().toPlainString()
                        + "% was applied to every line so vendors compare on the same basis"));
    }

    /** Returns null (and adds an ERROR flag) when quantity or unit price is missing. */
    LineAmounts calculateLine(QuotationItem item, List<FlagDraft> flags) {
        boolean missing = false;
        if (item.getQuantity() == null) {
            flags.add(FlagDraft.error(item, "quantity", FlagCodes.MISSING_QUANTITY,
                    "Line " + item.getLineNumber() + ": quantity is missing"));
            missing = true;
        }
        if (item.getUnitPrice() == null) {
            flags.add(FlagDraft.error(item, "unitPrice", FlagCodes.MISSING_UNIT_PRICE,
                    "Line " + item.getLineNumber() + ": unit price is missing"));
            missing = true;
        }
        if (missing) {
            return null;
        }

        BigDecimal base = item.getQuantity().multiply(item.getUnitPrice());

        BigDecimal discount;
        if (item.getDiscountAmount() != null) {
            discount = item.getDiscountAmount();
            if (item.getDiscountPercent() != null
                    && !approximatelyEqual(round(percentOf(base, item.getDiscountPercent())), round(discount))) {
                flags.add(FlagDraft.warning(item, "discountAmount", FlagCodes.DISCOUNT_CONFLICT,
                        "Line " + item.getLineNumber() + ": discount amount " + round(discount)
                                + " does not match " + item.getDiscountPercent().stripTrailingZeros().toPlainString()
                                + "% of " + round(base) + "; the amount was used"));
            }
        } else if (item.getDiscountPercent() != null) {
            discount = percentOf(base, item.getDiscountPercent());
        } else {
            discount = BigDecimal.ZERO;
        }
        BigDecimal taxable = base.subtract(discount);

        BigDecimal tax;
        if (item.getTaxAmount() != null) {
            tax = item.getTaxAmount();
            if (item.getTaxPercent() != null
                    && !approximatelyEqual(round(percentOf(taxable, item.getTaxPercent())), round(tax))) {
                flags.add(FlagDraft.warning(item, "taxAmount", FlagCodes.TAX_CONFLICT,
                        "Line " + item.getLineNumber() + ": tax amount " + round(tax) + " does not match "
                                + item.getTaxPercent().stripTrailingZeros().toPlainString() + "% of "
                                + round(taxable) + "; the amount was used"));
            }
        } else if (item.getTaxPercent() != null) {
            tax = percentOf(taxable, item.getTaxPercent());
        } else {
            tax = BigDecimal.ZERO;
        }

        BigDecimal total = round(taxable.add(tax));
        BigDecimal stated = item.getStatedLineTotal();
        // Vendors print line totals either before or after tax; both are accepted.
        if (stated != null && !approximatelyEqual(stated, total) && !approximatelyEqual(stated, round(taxable))) {
            flags.add(FlagDraft.warning(item, "statedLineTotal", FlagCodes.LINE_TOTAL_MISMATCH,
                    "Line " + item.getLineNumber() + ": document shows " + round(stated)
                            + " but quantity x price gives " + round(taxable) + " before tax and " + total
                            + " after tax"));
        }
        return new LineAmounts(round(base), round(discount), round(taxable), round(tax), total);
    }

    private void compareStated(List<FlagDraft> flags, String field, String code, String label,
                               BigDecimal stated, BigDecimal calculated, BigDecimal... alternatives) {
        if (stated == null || calculated == null) {
            return;
        }
        if (approximatelyEqual(stated, calculated)) {
            return;
        }
        for (BigDecimal alternative : alternatives) {
            if (alternative != null && approximatelyEqual(stated, alternative)) {
                return;
            }
        }
        flags.add(FlagDraft.warning(null, field, code, label + " on the document (" + round(stated)
                + ") differs from the calculated " + calculated + " by " + round(stated.subtract(calculated).abs())
                + ". Check for charges, rounding or extraction errors."));
    }

    public record LineAmounts(BigDecimal base, BigDecimal discount, BigDecimal taxable, BigDecimal tax,
                              BigDecimal total) {
    }
}
