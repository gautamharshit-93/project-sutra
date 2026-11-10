package com.sutra.quotation.service.internal;

import com.sutra.quotation.support.TestRfq;
import com.sutra.api.rules.FlagCodes;
import static org.assertj.core.api.Assertions.assertThat;

import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationItem;
import com.sutra.api.enums.FlagSeverity;
import com.sutra.common.security.RoleName;
import com.sutra.quotation.support.Fixtures;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotationCalculatorTest {

    private final QuotationCalculator calculator = new QuotationCalculator();
    private Quotation quotation;

    @BeforeEach
    void setUp() {
        TestRfq pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        quotation = Fixtures.quotation(pr, Fixtures.vendor("Alpha"));
    }

    @Test
    void computesLineAndQuotationTotalsWithDiscountAndTax() {
        QuotationItem item = Fixtures.item(quotation, null, "Laptop", "10", "70000", "18");
        item.setDiscountPercent(new BigDecimal("5"));

        List<FlagDraft> flags = calculator.recalculate(quotation);

        // 10 x 70000 = 700000; -5% = 665000; +18% = 784700
        assertThat(item.getLineTotal()).isEqualByComparingTo("784700.00");
        assertThat(quotation.getSubtotal()).isEqualByComparingTo("665000.00");
        assertThat(quotation.getDiscountTotal()).isEqualByComparingTo("35000.00");
        assertThat(quotation.getTaxTotal()).isEqualByComparingTo("119700.00");
        assertThat(quotation.getGrandTotal()).isEqualByComparingTo("784700.00");
        assertThat(flags).isEmpty();
    }

    @Test
    void roundsHalfUpToTwoDecimals() {
        QuotationItem item = Fixtures.item(quotation, null, "Cable", "3", "0.335", null);
        calculator.recalculate(quotation);
        assertThat(item.getLineTotal()).isEqualByComparingTo("1.01"); // 1.005 -> 1.01
    }

    @Test
    void missingPriceIsAnErrorAndLeavesTotalsEmpty() {
        Fixtures.item(quotation, null, "Laptop", "10", "70000", null);
        Fixtures.item(quotation, null, "Monitor", "10", null, null);

        List<FlagDraft> flags = calculator.recalculate(quotation);

        assertThat(flags).extracting(FlagDraft::code).containsExactly(FlagCodes.MISSING_UNIT_PRICE);
        assertThat(flags.get(0).severity()).isEqualTo(FlagSeverity.ERROR);
        assertThat(quotation.getGrandTotal()).as("partial totals would mislead").isNull();
    }

    @Test
    void flagsPrintedLineTotalThatDisagreesWithQuantityTimesPrice() {
        QuotationItem item = Fixtures.item(quotation, null, "Laptop", "10", "70000", null);
        item.setStatedLineTotal(new BigDecimal("650000"));

        List<FlagDraft> flags = calculator.recalculate(quotation);

        assertThat(flags).extracting(FlagDraft::code).containsExactly(FlagCodes.LINE_TOTAL_MISMATCH);
        assertThat(item.getLineTotal()).as("backend value wins").isEqualByComparingTo("700000.00");
    }

    @Test
    void acceptsPrintedLineTotalBeforeOrAfterTaxAndSmallRounding() {
        QuotationItem preTax = Fixtures.item(quotation, null, "A", "10", "100", "18");
        preTax.setStatedLineTotal(new BigDecimal("1000"));
        QuotationItem postTax = Fixtures.item(quotation, null, "B", "3", "33.33", "18");
        postTax.setStatedLineTotal(new BigDecimal("118.00")); // 117.99 calculated

        assertThat(calculator.recalculate(quotation)).isEmpty();
    }

    @Test
    void flagsConflictingDiscountAndUsesTheAmount() {
        QuotationItem item = Fixtures.item(quotation, null, "Laptop", "1", "1000", null);
        item.setDiscountPercent(new BigDecimal("10"));
        item.setDiscountAmount(new BigDecimal("50"));

        List<FlagDraft> flags = calculator.recalculate(quotation);

        assertThat(flags).extracting(FlagDraft::code).contains(FlagCodes.DISCOUNT_CONFLICT);
        assertThat(item.getLineTotal()).isEqualByComparingTo("950.00");
    }

    @Test
    void flagsGrandTotalMismatch() {
        Fixtures.item(quotation, null, "Laptop", "1", "1000", "18");
        quotation.setStatedGrandTotal(new BigDecimal("1250"));

        List<FlagDraft> flags = calculator.recalculate(quotation);

        assertThat(flags).extracting(FlagDraft::code).containsExactly(FlagCodes.GRAND_TOTAL_MISMATCH);
        assertThat(flags.get(0).message()).contains("1250.00", "1180.00");
    }

    @Test
    void acceptsSubtotalPrintedBeforeDiscount() {
        // Northstar (data pack): laptops 20 x 62,500 with 2% discount; the document prints the gross subtotal.
        QuotationItem laptop = Fixtures.item(quotation, null, "Business Laptop", "20", "62500", "18");
        laptop.setDiscountPercent(new BigDecimal("2"));
        quotation.setStatedSubtotal(new BigDecimal("1250000.00")); // before discount
        quotation.setStatedGrandTotal(new BigDecimal("1445500.00"));

        assertThat(calculator.recalculate(quotation)).isEmpty();
        assertThat(quotation.getSubtotal()).isEqualByComparingTo("1225000.00"); // after discount
    }

    @Test
    void appliesDocumentLevelTaxToEveryLine() {
        QuotationItem laptop = Fixtures.item(quotation, null, "Laptop", "10", "66900", null);
        QuotationItem monitor = Fixtures.item(quotation, null, "Monitor", "10", "10450", null);
        quotation.setStatedTaxTotal(new BigDecimal("139230.00")); // 18% of 773500
        quotation.setStatedGrandTotal(new BigDecimal("912730.00"));

        List<FlagDraft> flags = calculator.recalculate(quotation);

        assertThat(laptop.getTaxPercent()).isEqualByComparingTo("18");
        assertThat(monitor.getLineTotal()).isEqualByComparingTo("123310.00");
        assertThat(quotation.getGrandTotal()).isEqualByComparingTo("912730.00");
        assertThat(flags).extracting(FlagDraft::code).containsExactly(FlagCodes.DOCUMENT_LEVEL_TAX);
        assertThat(flags.get(0).severity()).isEqualTo(FlagSeverity.INFO);
    }

    @Test
    void doesNotInventTaxWhenLinesAlreadyCarryIt() {
        QuotationItem laptop = Fixtures.item(quotation, null, "Laptop", "1", "1000", "12");
        quotation.setStatedTaxTotal(new BigDecimal("180"));

        calculator.recalculate(quotation);

        assertThat(laptop.getTaxPercent()).isEqualByComparingTo("12");
    }
}
