package com.sutra.quotation.service.internal;

import com.sutra.quotation.support.TestRfq;
import com.sutra.api.rules.FlagCodes;
import static org.assertj.core.api.Assertions.assertThat;

import com.sutra.quotation.entity.Quotation;
import com.sutra.api.enums.FlagSeverity;
import com.sutra.common.security.RoleName;
import com.sutra.quotation.support.Fixtures;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotationValidatorTest {

    private final QuotationValidator validator =
            new QuotationValidator(new QuotationCalculator(), Fixtures.properties(), Fixtures.CLOCK);
    private TestRfq pr;
    private TestRfq.Item laptops;
    private TestRfq.Item monitors;
    private Quotation quotation;

    @BeforeEach
    void setUp() {
        pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        laptops = pr.getItems().get(0);
        monitors = pr.getItems().get(1);
        quotation = Fixtures.quotation(pr, Fixtures.vendor("Alpha"));
    }

    @Test
    void completeMatchingQuotationHasNoFlags() {
        Fixtures.item(quotation, laptops, "Dell Latitude 5440", "10", "68500", "18");
        Fixtures.item(quotation, monitors, "Dell 24in monitor", "10", "11200", "18");

        assertThat(validator.validate(quotation, pr.toResponse())).isEmpty();
    }

    @Test
    void flagsUnquotedRequestLineAndUnmatchedQuotedLine() {
        Fixtures.item(quotation, laptops, "Dell Latitude 5440", "10", "68500", "18");
        Fixtures.item(quotation, null, "Installation charges", "1", "5000", "18");

        List<FlagDraft> flags = validator.validate(quotation, pr.toResponse());

        assertThat(flags).extracting(FlagDraft::code)
                .containsExactlyInAnyOrder(FlagCodes.UNMATCHED_ITEM, FlagCodes.MISSING_REQUEST_ITEM);
        assertThat(flags).allMatch(f -> f.severity() == FlagSeverity.WARNING);
    }

    @Test
    void flagsQuantityDifferenceAndLowMatchConfidence() {
        var item = Fixtures.item(quotation, laptops, "Laptop", "8", "68500", null);
        item.setMatchConfidence(new BigDecimal("0.410"));
        Fixtures.item(quotation, monitors, "Monitor", "10", "11200", null);

        assertThat(validator.validate(quotation, pr.toResponse())).extracting(FlagDraft::code)
                .containsExactlyInAnyOrder(FlagCodes.QUANTITY_MISMATCH, FlagCodes.LOW_MATCH_CONFIDENCE);
    }

    @Test
    void lowMatchConfidenceIsIgnoredOnceAPersonEditedTheLine() {
        var item = Fixtures.item(quotation, laptops, "Laptop", "10", "68500", null);
        item.setMatchConfidence(new BigDecimal("0.410"));
        item.setManuallyEdited(true);
        Fixtures.item(quotation, monitors, "Monitor", "10", "11200", null);

        assertThat(validator.validate(quotation, pr.toResponse())).isEmpty();
    }

    @Test
    void flagsCommercialTermsCurrencyAndExpiredValidity() {
        Fixtures.item(quotation, laptops, "Laptop", "10", "68500", null);
        Fixtures.item(quotation, monitors, "Monitor", "10", "11200", null);
        quotation.setCurrency("USD");
        quotation.setValidUntil(Fixtures.TODAY.minusDays(1));
        quotation.setPaymentTerms(null);
        quotation.setDeliveryTerms(" ");

        List<FlagDraft> flags = validator.validate(quotation, pr.toResponse());

        assertThat(flags).extracting(FlagDraft::code).containsExactlyInAnyOrder(FlagCodes.CURRENCY_MISMATCH,
                FlagCodes.VALIDITY_EXPIRED, FlagCodes.MISSING_PAYMENT_TERMS, FlagCodes.MISSING_DELIVERY_TERMS);
    }

    @Test
    void flagsTermsTheVendorLeftOpenAndConditionalOffers() {
        Fixtures.item(quotation, laptops, "Laptop", "10", "68500", null);
        Fixtures.item(quotation, monitors, "Monitor", "10", "11200", null);
        quotation.setWarrantyTerms("Laptop: 3 years; monitor: 3 years. UPS warranty not specified in this quotation.");
        quotation.setNotes("All requested items quoted. Quoted prices remain subject to stock availability.");

        List<FlagDraft> flags = validator.validate(quotation, pr.toResponse());

        assertThat(flags).extracting(FlagDraft::code)
                .containsExactlyInAnyOrder(FlagCodes.INCOMPLETE_TERMS, FlagCodes.CONDITIONAL_TERMS);
        FlagDraft warranty = flags.stream().filter(f -> f.code().equals(FlagCodes.INCOMPLETE_TERMS)).findFirst().orElseThrow();
        assertThat(warranty.severity()).isEqualTo(FlagSeverity.WARNING);
        assertThat(warranty.message()).contains("Warranty terms are incomplete", "UPS warranty not specified");
    }

    @Test
    void unitAliasesAreEquivalent() {
        assertThat(QuotationValidator.normaliseUom("Nos.")).isEqualTo(QuotationValidator.normaliseUom("pcs"));
        assertThat(QuotationValidator.normaliseUom("Each")).isEqualTo("unit");
        assertThat(QuotationValidator.normaliseUom("kg")).isNotEqualTo(QuotationValidator.normaliseUom("Nos"));
    }
}
