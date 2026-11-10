package com.sutra.comparison.service.internal;

import com.sutra.comparison.support.Fixtures;
import com.sutra.comparison.support.TestQuotation;
import com.sutra.api.comparison.GradeReport;
import static org.assertj.core.api.Assertions.assertThat;

import com.sutra.api.comparison.GradeReport.GradeRule;
import com.sutra.common.security.RoleName;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The advisory vendor grade: every rule, and the letters. */
class VendorGraderTest {

    private static TestQuotation quotation() {
        TestQuotation q = Fixtures.quotation(Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT)), "Northstar");
        q.setDeliveryTerms("Within 12 calendar days");
        q.setPaymentTerms("30 days credit");
        q.setWarrantyTerms("3 years onsite");
        q.setValidUntil(Fixtures.TODAY.plusDays(30));
        return q;
    }

    private static GradeRule rule(GradeReport report, String code) {
        return report.rules().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void lowestCompleteQuotationWithGoodTermsGetsAnA() {
        GradeReport report = VendorGrader.grade(quotation().toResponse(), 1, BigDecimal.ZERO, 2, 2, Fixtures.TODAY);

        assertThat(report.score()).isEqualTo(100);
        assertThat(report.grade()).isEqualTo("A");
        assertThat(report.rules()).extracting(GradeRule::maxPoints).containsExactly(40, 15, 10, 10, 10, 10, 5);
    }

    @Test
    void priceLosesTwoPointsPerPercentAboveTheLowest() {
        GradeReport report = VendorGrader.grade(quotation().toResponse(), 2, new BigDecimal("1.5"), 2, 2, Fixtures.TODAY);

        assertThat(rule(report, "PRICE").points()).isEqualByComparingTo("37.0");
        assertThat(rule(report, "PRICE").detail()).isEqualTo("Rank 2, 1.5% above lowest");
        assertThat(VendorGrader.grade(quotation().toResponse(), 3, new BigDecimal("25"), 2, 2, Fixtures.TODAY)
                .rules().get(0).points()).isEqualByComparingTo("0");
    }

    @Test
    void unrankedQuotationGetsNoPricePointsAndPartialCoverage() {
        GradeReport report = VendorGrader.grade(quotation().toResponse(), null, null, 1, 2, Fixtures.TODAY);

        assertThat(rule(report, "PRICE").points()).isEqualByComparingTo("0");
        assertThat(rule(report, "COVERAGE").points()).isEqualByComparingTo("7.5");
        assertThat(report.score()).isEqualTo(53); // 0 + 7.5 + 10 + 10 + 10 + 10 + 5 = 52.5, rounded half up
        assertThat(report.grade()).isEqualTo("D");
    }

    @Test
    void deliveryScoresTheLongestStatedTime() {
        assertThat(VendorGrader.delivery("2-3 weeks").points()).isEqualByComparingTo("8.0");
        assertThat(VendorGrader.delivery("45 days from PO").points()).isEqualByComparingTo("6.0");
        assertThat(VendorGrader.delivery("Ex-stock").detail()).isEqualTo("No delivery time in days");
        assertThat(VendorGrader.delivery("To be confirmed").points()).isEqualByComparingTo("4.0");
        assertThat(VendorGrader.delivery(null).points()).isEqualByComparingTo("0");
        assertThat(VendorGrader.days("within 10 working days, installation in 2 weeks")).isEqualTo(14);
    }

    @Test
    void paymentPrefersCreditOverAdvance() {
        assertThat(VendorGrader.payment("100% advance").detail()).isEqualTo("Full payment in advance");
        assertThat(VendorGrader.payment("50% advance, balance on delivery").points()).isEqualByComparingTo("7.0");
        assertThat(VendorGrader.payment("Full payment upfront").points()).isEqualByComparingTo("4.0");
        assertThat(VendorGrader.payment("45 days from invoice").points()).isEqualByComparingTo("10.0");
        assertThat(VendorGrader.payment("").points()).isEqualByComparingTo("0");
    }

    @Test
    void warrantyLeftOpenEarnsHalf() {
        GradeRule rule = VendorGrader.warranty("Laptops 3 years. UPS warranty not specified.");

        assertThat(rule.points()).isEqualByComparingTo("5.0");
        assertThat(rule.detail()).contains("UPS warranty not specified");
    }

    @Test
    void validityNearExpiryOrExpiredLosesPoints() {
        assertThat(VendorGrader.validity(Fixtures.TODAY.plusDays(3), Fixtures.TODAY).points()).isEqualByComparingTo("6.0");
        assertThat(VendorGrader.validity(Fixtures.TODAY.minusDays(1), Fixtures.TODAY).detail()).startsWith("Expired");
        assertThat(VendorGrader.validity(null, Fixtures.TODAY).points()).isEqualByComparingTo("0");
    }

    @Test
    void openReviewFlagsReduceDataQuality() {
        TestQuotation q = quotation();
        q.addOpenFlag();
        q.addOpenFlag();

        GradeRule rule = rule(VendorGrader.grade(q.toResponse(), 1, BigDecimal.ZERO, 2, 2, Fixtures.TODAY), "DATA_QUALITY");

        assertThat(rule.points()).isEqualByComparingTo("3.0");
        assertThat(rule.detail()).isEqualTo("2 open");
    }

    @Test
    void lettersFollowTheDocumentedBands() {
        assertThat(GradeReport.letter(85)).isEqualTo("A");
        assertThat(GradeReport.letter(84)).isEqualTo("B");
        assertThat(GradeReport.letter(55)).isEqualTo("C");
        assertThat(GradeReport.letter(40)).isEqualTo("D");
        assertThat(GradeReport.letter(39)).isEqualTo("F");
    }
}
