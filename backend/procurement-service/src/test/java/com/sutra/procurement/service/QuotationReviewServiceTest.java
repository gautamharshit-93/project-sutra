package com.sutra.procurement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sutra.common.security.RoleName;
import com.sutra.procurement.calculation.FlagCodes;
import com.sutra.procurement.calculation.QuotationCalculator;
import com.sutra.procurement.calculation.QuotationValidator;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationFlag;
import com.sutra.procurement.entity.UserRef;
import com.sutra.procurement.entity.enums.FlagSeverity;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flags are regenerated from the data, acknowledged flags stay quiet, and status follows the open flags. */
class QuotationReviewServiceTest {

    private final QuotationReviewService review = new QuotationReviewService(
            new QuotationValidator(new QuotationCalculator(), Fixtures.properties(), Fixtures.CLOCK));
    private PurchaseRequest pr;
    private Quotation q;

    @BeforeEach
    void setUp() {
        pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        q = Fixtures.quotation(pr, Fixtures.vendor("Shakti Metals"));
        q.setWarrantyTerms("12 months");
        Fixtures.item(q, pr.getItems().get(0), "Laptop", "10", "68500", "18");
        Fixtures.item(q, pr.getItems().get(1), "Monitor", "10", "11200", "18");
    }

    @Test
    void cleanQuotationIsReadyForComparison() {
        review.revalidate(q);

        assertThat(q.getFlags()).noneMatch(f -> f.getSeverity() != FlagSeverity.INFO);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
    }

    @Test
    void missingRequestedItemIsANoteButStillComparable() {
        q.getItems().remove(1);

        review.revalidate(q);

        assertThat(q.getFlags()).extracting(QuotationFlag::getCode).contains(FlagCodes.MISSING_REQUEST_ITEM);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
    }

    @Test
    void onlyALineWithoutPriceOrQuantityNeedsReview() {
        q.getItems().get(0).setUnitPrice(null);

        review.revalidate(q);

        assertThat(q.getFlags()).extracting(QuotationFlag::getCode).contains(FlagCodes.MISSING_UNIT_PRICE);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.NEEDS_REVIEW);
    }

    @Test
    void acknowledgedWarningIsNotRaisedAgain() {
        q.getItems().remove(1);
        review.revalidate(q);
        q.getFlags().stream().filter(f -> f.getSeverity() == FlagSeverity.WARNING)
                .forEach(f -> f.resolve(new UserRef(1L, "buyer")));

        review.revalidate(q);

        assertThat(q.getFlags()).filteredOn(f -> !f.isResolved() && f.getSeverity() == FlagSeverity.WARNING).isEmpty();
    }

    @Test
    void validationFlagsAreRegeneratedFromTheCurrentData() {
        q.getItems().remove(1);
        review.revalidate(q);
        Fixtures.item(q, pr.getItems().get(1), "Monitor", "10", "11200", "18");

        review.revalidate(q);

        assertThat(q.getFlags()).extracting(QuotationFlag::getCode).doesNotContain(FlagCodes.MISSING_REQUEST_ITEM);
        assertThat(q.getStatus()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
    }

    @Test
    void failedQuotationStaysFailedUntilALineIsEntered() {
        Quotation failed = Fixtures.quotation(pr, Fixtures.vendor("Pioneer"));
        failed.setStatus(QuotationStatus.FAILED);

        review.updateStatus(failed);
        assertThat(failed.getStatus()).isEqualTo(QuotationStatus.FAILED);

        Fixtures.item(failed, pr.getItems().get(0), "Laptop", "10", "68500", "18");
        review.updateStatus(failed);
        assertThat(failed.getStatus()).isNotEqualTo(QuotationStatus.FAILED);
    }

    @Test
    void openErrorIsDetected() {
        q.addFlag(new QuotationFlag(null, "items", FlagSeverity.ERROR, FlagCodes.NO_ITEMS_EXTRACTED, "none"));

        assertThat(QuotationReviewService.hasOpenErrors(q)).isTrue();
        assertThat(QuotationReviewService.hasBlockingFlags(q)).isTrue();
        assertThat(Instant.now()).isNotNull();
    }
}
