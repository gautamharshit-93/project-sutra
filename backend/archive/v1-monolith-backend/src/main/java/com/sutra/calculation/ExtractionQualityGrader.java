package com.sutra.calculation;

import com.sutra.calculation.GradeReport.GradeRule;
import com.sutra.entity.Quotation;
import com.sutra.entity.QuotationFlag;
import com.sutra.entity.QuotationItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Grades how trustworthy a quotation's extracted data is, so engines can be compared and a reviewer knows how much
 * checking is needed. Works on stored quotations and on unsaved cross-check previews alike.
 *
 * <table>
 *   <caption>Rules (100 points)</caption>
 *   <tr><td>ITEMS_FOUND</td><td>15</td><td>At least one priced line was read</td></tr>
 *   <tr><td>REQUEST_COVERAGE</td><td>20</td><td>Share of requested items matched to a quoted line</td></tr>
 *   <tr><td>LINES_COMPLETE</td><td>15</td><td>Share of lines with quantity, unit price and a calculated total</td></tr>
 *   <tr><td>TOTAL_RECONCILES</td><td>20</td><td>Backend grand total equals the printed grand total
 *       (half when no total is printed)</td></tr>
 *   <tr><td>LINE_ARITHMETIC</td><td>10</td><td>Share of lines whose printed total matches the backend's</td></tr>
 *   <tr><td>HEADER_FIELDS</td><td>10</td><td>Quotation number, date, validity and currency found</td></tr>
 *   <tr><td>TERMS_FOUND</td><td>5</td><td>Delivery, payment and warranty terms found</td></tr>
 *   <tr><td>CONFIDENCE</td><td>5</td><td>The engine's own confidence</td></tr>
 * </table>
 */
public final class ExtractionQualityGrader {

    private ExtractionQualityGrader() {
    }

    public static GradeReport grade(Quotation q) {
        List<GradeRule> rules = new ArrayList<>();
        List<QuotationItem> items = q.getItems();
        int lines = items.size();
        int requested = q.getPurchaseRequest().getItems().size();

        rules.add(GradeReport.rule("ITEMS_FOUND", "Line items read", 15, lines > 0 ? 1 : 0,
                lines + " line(s)"));

        long matched = items.stream().map(QuotationItem::getRequestItem).filter(Objects::nonNull).distinct().count();
        rules.add(GradeReport.rule("REQUEST_COVERAGE", "Requested items matched", 20,
                requested == 0 ? 0 : (double) matched / requested, matched + " of " + requested));

        long complete = items.stream()
                .filter(i -> i.getQuantity() != null && i.getUnitPrice() != null && i.getLineTotal() != null).count();
        rules.add(GradeReport.rule("LINES_COMPLETE", "Lines with quantity, price and total", 15,
                lines == 0 ? 0 : (double) complete / lines, complete + " of " + lines));

        boolean totalMismatch = hasFlag(q, FlagCodes.GRAND_TOTAL_MISMATCH);
        double totalScore;
        String totalDetail;
        if (q.getGrandTotal() == null) {
            totalScore = 0;
            totalDetail = "Backend could not calculate a grand total";
        } else if (q.getStatedGrandTotal() == null) {
            totalScore = 0.5;
            totalDetail = "No printed grand total to check against";
        } else if (totalMismatch) {
            totalScore = 0;
            totalDetail = "Printed " + plain(q.getStatedGrandTotal()) + " vs calculated " + plain(q.getGrandTotal());
        } else {
            totalScore = 1;
            totalDetail = "Printed and calculated totals agree (" + plain(q.getGrandTotal()) + ")";
        }
        rules.add(GradeReport.rule("TOTAL_RECONCILES", "Grand total reconciles", 20, totalScore, totalDetail));

        long lineMismatches = q.getFlags().stream()
                .filter(f -> FlagCodes.LINE_TOTAL_MISMATCH.equals(f.getCode()) && f.getQuotationItem() != null)
                .map(QuotationFlag::getQuotationItem).distinct().count();
        rules.add(GradeReport.rule("LINE_ARITHMETIC", "Line totals match", 10,
                lines == 0 ? 0 : 1 - (double) lineMismatches / lines,
                lineMismatches == 0 ? "No line total differences" : lineMismatches + " line(s) differ"));

        List<String> missingHeader = new ArrayList<>();
        if (blank(q.getQuotationNumber())) {
            missingHeader.add("number");
        }
        if (q.getQuotationDate() == null) {
            missingHeader.add("date");
        }
        if (q.getValidUntil() == null) {
            missingHeader.add("validity");
        }
        if (blank(q.getCurrency())) {
            missingHeader.add("currency");
        }
        rules.add(GradeReport.rule("HEADER_FIELDS", "Quotation number, date, validity, currency", 10,
                (4 - missingHeader.size()) / 4.0,
                missingHeader.isEmpty() ? "All found" : "Missing: " + String.join(", ", missingHeader)));

        List<String> missingTerms = new ArrayList<>();
        if (blank(q.getDeliveryTerms())) {
            missingTerms.add("delivery");
        }
        if (blank(q.getPaymentTerms())) {
            missingTerms.add("payment");
        }
        if (blank(q.getWarrantyTerms())) {
            missingTerms.add("warranty");
        }
        rules.add(GradeReport.rule("TERMS_FOUND", "Delivery, payment and warranty terms", 5,
                (3 - missingTerms.size()) / 3.0,
                missingTerms.isEmpty() ? "All found" : "Missing: " + String.join(", ", missingTerms)));

        BigDecimal confidence = q.getExtractionConfidence();
        rules.add(GradeReport.rule("CONFIDENCE", "Engine confidence", 5,
                confidence == null ? 0 : confidence.doubleValue(),
                confidence == null ? "Not reported" : confidence.stripTrailingZeros().toPlainString()));

        return GradeReport.of(rules);
    }

    private static boolean hasFlag(Quotation q, String code) {
        return q.getFlags().stream().anyMatch(f -> code.equals(f.getCode()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
