package com.sutra.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sutra.calculation.ComparisonResult.ItemCell;
import com.sutra.calculation.ComparisonResult.QuotationColumn;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.Quotation;
import com.sutra.entity.RequestItem;
import com.sutra.entity.enums.RoleName;
import com.sutra.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ComparisonEngineTest {

    private final QuotationCalculator calculator = new QuotationCalculator();
    private final ComparisonEngine engine = new ComparisonEngine(Fixtures.CLOCK);
    private PurchaseRequest pr;
    private RequestItem laptops;
    private RequestItem monitors;

    @BeforeEach
    void setUp() {
        pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        laptops = pr.getItems().get(0);
        monitors = pr.getItems().get(1);
    }

    private Quotation quote(String vendor, String laptopPrice, String monitorPrice) {
        Quotation q = Fixtures.quotation(pr, Fixtures.vendor(vendor));
        if (laptopPrice != null) {
            Fixtures.item(q, laptops, "Laptop", "10", laptopPrice, "18");
        }
        if (monitorPrice != null) {
            Fixtures.item(q, monitors, "Monitor", "10", monitorPrice, "18");
        }
        calculator.recalculate(q);
        return q;
    }

    @Test
    void ranksCompleteQuotationsAndMarksLowestPerItem() {
        Quotation a = quote("Alpha", "68500", "11200");
        Quotation b = quote("Bravo", "66900", "12000");

        ComparisonResult result = engine.compare(pr, List.of(a, b));

        // Alpha: (685000+112000)*1.18 = 940460 ; Bravo: (669000+120000)*1.18 = 931020
        assertThat(result.lowestCompleteQuotationId()).isEqualTo(b.getId());
        QuotationColumn alpha = column(result, a);
        assertThat(alpha.priceRank()).isEqualTo(2);
        assertThat(alpha.comparableTotal()).isEqualByComparingTo("940460.00");
        assertThat(alpha.differenceFromLowest()).isEqualByComparingTo("9440.00");
        assertThat(alpha.differencePercent()).isEqualByComparingTo("1.01");

        ItemCell alphaMonitor = cell(result, 1, a);
        assertThat(alphaMonitor.lowest()).isTrue();
        assertThat(cell(result, 0, b).lowest()).isTrue();
        assertThat(result.disclaimer()).contains("never selects or approves");
    }

    @Test
    void incompleteQuotationIsShownButNotRanked() {
        Quotation a = quote("Alpha", "68500", "11200");
        Quotation cheapButIncomplete = quote("Cheap", "50000", null);

        ComparisonResult result = engine.compare(pr, List.of(a, cheapButIncomplete));

        QuotationColumn cheap = column(result, cheapButIncomplete);
        assertThat(cheap.complete()).isFalse();
        assertThat(cheap.priceRank()).isNull();
        assertThat(cheap.itemsMissing()).isEqualTo(1);
        assertThat(cheap.missingInformation()).contains("1 requested item(s) not quoted");
        assertThat(cell(result, 1, cheapButIncomplete).status()).isEqualTo("MISSING");
        assertThat(result.lowestCompleteQuotationId()).isEqualTo(a.getId());
    }

    @Test
    void normalisesToRequestedQuantityWhenVendorQuotesDifferentQuantity() {
        Quotation a = quote("Alpha", "68500", "11200");
        Quotation partial = Fixtures.quotation(pr, Fixtures.vendor("Partial"));
        Fixtures.item(partial, laptops, "Laptop", "5", "60000", "18");   // only 5 laptops quoted
        Fixtures.item(partial, monitors, "Monitor", "10", "11200", "18");
        calculator.recalculate(partial);

        ComparisonResult result = engine.compare(pr, List.of(a, partial));

        ItemCell laptopCell = cell(result, 0, partial);
        assertThat(laptopCell.quantityMismatch()).isTrue();
        assertThat(laptopCell.effectiveUnitPrice()).isEqualByComparingTo("70800.0000");
        assertThat(laptopCell.comparableLineTotal()).isEqualByComparingTo("708000.00"); // for the requested 10
    }

    @Test
    void otherCurrencyIsExcludedFromRankingWithWarning() {
        Quotation a = quote("Alpha", "68500", "11200");
        Quotation usd = quote("Dollar", "800", "150");
        usd.setCurrency("USD");

        ComparisonResult result = engine.compare(pr, List.of(a, usd));

        assertThat(column(result, usd).priceRank()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("USD"));
        assertThat(cell(result, 0, usd).lowest()).isFalse();
    }

    @Test
    void incompleteWarrantyIsListedAsMissingInformation() {
        Quotation apex = quote("Apex", "63000", "10000");
        apex.setWarrantyTerms("Laptop: 3 years. UPS warranty not specified in this quotation.");

        ComparisonResult result = engine.compare(pr, List.of(apex));

        assertThat(column(result, apex).missingInformation()).contains("Warranty incomplete");
    }

    @Test
    void listsUnmatchedExtraLines() {
        Quotation a = quote("Alpha", "68500", "11200");
        Fixtures.item(a, null, "Installation", "1", "5000", null);
        calculator.recalculate(a);

        ComparisonResult result = engine.compare(pr, List.of(a));

        assertThat(result.unmatchedItems()).singleElement()
                .satisfies(u -> assertThat(u.description()).isEqualTo("Installation"));
    }

    private static QuotationColumn column(ComparisonResult result, Quotation q) {
        return result.quotations().stream().filter(c -> c.quotationId().equals(q.getId())).findFirst().orElseThrow();
    }

    private static ItemCell cell(ComparisonResult result, int row, Quotation q) {
        return result.rows().get(row).cells().stream().filter(c -> c.quotationId().equals(q.getId()))
                .findFirst().orElseThrow();
    }
}
