package com.sutra.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sutra.calculation.GradeReport.GradeRule;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.Quotation;
import com.sutra.entity.QuotationFlag;
import com.sutra.entity.enums.FlagSeverity;
import com.sutra.entity.enums.RoleName;
import com.sutra.support.Fixtures;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** How trustworthy an extraction is, rule by rule. */
class ExtractionQualityGraderTest {

    private final QuotationCalculator calculator = new QuotationCalculator();

    /** Two matched lines, recalculated by the backend, with every header field and term present. */
    private Quotation complete() {
        PurchaseRequest pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        Quotation q = Fixtures.quotation(pr, Fixtures.vendor("Northstar"));
        Fixtures.item(q, pr.getItems().get(0), "Laptop", "10", "68500", "18");
        Fixtures.item(q, pr.getItems().get(1), "Monitor", "10", "11200", "18");
        q.setQuotationNumber("NS-118");
        q.setQuotationDate(Fixtures.TODAY);
        q.setWarrantyTerms("3 years");
        q.setExtractionConfidence(new BigDecimal("0.900"));
        calculator.recalculate(q);
        q.setStatedGrandTotal(q.getGrandTotal());
        return q;
    }

    private static GradeRule rule(GradeReport report, String code) {
        return report.rules().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void completeReconciledExtractionScoresAnA() {
        GradeReport report = ExtractionQualityGrader.grade(complete());

        assertThat(report.grade()).isEqualTo("A");
        assertThat(report.score()).isEqualTo(100); // 99.5 points: engine confidence 0.9 earns 4.5 of 5
        assertThat(rule(report, "TOTAL_RECONCILES").detail()).startsWith("Printed and calculated totals agree");
        assertThat(report.rules()).extracting(GradeRule::maxPoints).containsExactly(15, 20, 15, 20, 10, 10, 5, 5);
    }

    @Test
    void grandTotalMismatchLosesAllReconciliationPoints() {
        Quotation q = complete();
        q.setStatedGrandTotal(new BigDecimal("999999.00"));
        q.addFlag(new QuotationFlag(null, "grandTotal", FlagSeverity.WARNING, FlagCodes.GRAND_TOTAL_MISMATCH, "differs"));

        GradeRule rule = rule(ExtractionQualityGrader.grade(q), "TOTAL_RECONCILES");

        assertThat(rule.points()).isEqualByComparingTo("0");
        assertThat(rule.detail()).contains("Printed 999999 vs calculated");
    }

    @Test
    void missingPrintedTotalEarnsHalf() {
        Quotation q = complete();
        q.setStatedGrandTotal(null);

        assertThat(rule(ExtractionQualityGrader.grade(q), "TOTAL_RECONCILES").points()).isEqualByComparingTo("10.0");
    }

    @Test
    void unmatchedLinesAndMissingHeaderFieldsAreCounted() {
        Quotation q = complete();
        q.getItems().get(1).setRequestItem(null);
        q.setQuotationNumber(null);
        q.setValidUntil(null);
        q.setWarrantyTerms(" ");

        GradeReport report = ExtractionQualityGrader.grade(q);

        assertThat(rule(report, "REQUEST_COVERAGE").detail()).isEqualTo("1 of 2");
        assertThat(rule(report, "HEADER_FIELDS").detail()).isEqualTo("Missing: number, validity");
        assertThat(rule(report, "TERMS_FOUND").detail()).isEqualTo("Missing: warranty");
    }

    @Test
    void lineTotalDifferencesAreCountedOncePerLine() {
        Quotation q = complete();
        var line = q.getItems().get(0);
        q.addFlag(new QuotationFlag(line, "lineTotal", FlagSeverity.WARNING, FlagCodes.LINE_TOTAL_MISMATCH, "a"));
        q.addFlag(new QuotationFlag(line, "lineTotal", FlagSeverity.WARNING, FlagCodes.LINE_TOTAL_MISMATCH, "b"));

        GradeRule rule = rule(ExtractionQualityGrader.grade(q), "LINE_ARITHMETIC");

        assertThat(rule.points()).isEqualByComparingTo("5.0");
        assertThat(rule.detail()).isEqualTo("1 line(s) differ");
    }

    @Test
    void emptyExtractionFails() {
        PurchaseRequest pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        Quotation q = Fixtures.quotation(pr, Fixtures.vendor("Northstar"));

        GradeReport report = ExtractionQualityGrader.grade(q);

        assertThat(report.grade()).isEqualTo("F");
        assertThat(rule(report, "ITEMS_FOUND").points()).isEqualByComparingTo("0");
        assertThat(rule(report, "CONFIDENCE").detail()).isEqualTo("Not reported");
    }
}
