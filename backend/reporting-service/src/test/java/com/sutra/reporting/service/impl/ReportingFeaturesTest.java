package com.sutra.reporting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.comparison.ComparisonResult.QuotationColumn;
import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.enums.ApprovalDecision;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.internal.InternalContracts.QuotationRef;
import com.sutra.api.quotation.QuotationResponses.QuotationItemResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.common.web.PageResponse;
import com.sutra.reporting.client.PlatformClient;
import com.sutra.reporting.dto.response.Badges.StaffBadges;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Menu badges and the award summary PDF, built from the other services' data. */
class ReportingFeaturesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static SutraUserPrincipal user(long id, String name, RoleName role) {
        return new SutraUserPrincipal(id, name, null, true, Set.of(role));
    }

    private static PurchaseRequestResponse rfq(long id, PurchaseRequestStatus status, LocalDate due) {
        return new PurchaseRequestResponse(id, "PR-2026-0000" + id, "Laptops for new joiners", null, status, 1L,
                "Head Office", null, null, null, null, 1L, "buyer", "INR", null, true, List.of(),
                Instant.parse("2026-10-30T06:00:00Z"), Instant.parse("2026-10-30T06:00:00Z"), due);
    }

    @Nested
    class Badges {
        private final PlatformClient platform = mock(PlatformClient.class);
        private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        private final BadgeServiceImpl badges = new BadgeServiceImpl(platform, currentUser, CLOCK);

        @Test
        void buyersSeeTheirOwnOpenRfqsWithQuotationsReadyToCompare() {
            SutraUserPrincipal buyer = user(1L, "buyer", RoleName.PROCUREMENT);
            when(currentUser.requirePrincipal()).thenReturn(buyer);
            when(platform.openRfqIds(1L)).thenReturn(List.of(10L, 11L, 12L));
            when(platform.rfqsWithReadyQuotations(List.of(10L, 11L, 12L))).thenReturn(List.of(10L, 12L));

            assertThat(badges.staff()).isEqualTo(new StaffBadges(2, 0));
            verify(platform, never()).awaitingAward(any());
        }

        @Test
        void managersSeeComparedRfqsWaitingForAnAwardExceptTheirOwn() {
            when(currentUser.requirePrincipal()).thenReturn(user(2L, "manager", RoleName.APPROVER));
            when(platform.awaitingAward(2L)).thenReturn(3L);

            assertThat(badges.staff()).isEqualTo(new StaffBadges(0, 3));
        }

        @Test
        void administratorsSeeEverything() {
            when(currentUser.requirePrincipal()).thenReturn(user(3L, "admin", RoleName.ADMIN));
            when(platform.openRfqIds(isNull())).thenReturn(List.of(10L, 11L, 12L, 13L, 14L));
            when(platform.rfqsWithReadyQuotations(any())).thenReturn(List.of(10L, 11L, 12L, 13L));
            when(platform.awaitingAward(isNull())).thenReturn(1L);

            assertThat(badges.staff()).isEqualTo(new StaffBadges(4, 1));
        }

        @Test
        void vendorsSeeOpenRfqsTheyHaveNotQuotedOnAndTheirDecidedQuotations() {
            when(currentUser.requirePrincipal()).thenReturn(
                    new SutraUserPrincipal(4L, "brakeline", null, true, Set.of(RoleName.VENDOR), 70L, 0));
            PurchaseRequestResponse quoted = rfq(1, PurchaseRequestStatus.OPEN, null);
            PurchaseRequestResponse notQuoted = rfq(2, PurchaseRequestStatus.OPEN, null);
            PurchaseRequestResponse pastDue = rfq(3, PurchaseRequestStatus.OPEN, TODAY.minusDays(1));
            PurchaseRequestResponse awarded = rfq(4, PurchaseRequestStatus.APPROVED, null);
            when(platform.quotationsOf(70L)).thenReturn(List.of(new QuotationRef(41L, 4L), new QuotationRef(40L, 1L)));
            when(platform.vendorView(any(), anyInt(), anyInt())).thenReturn(
                    new PageResponse<>(List.of(quoted, notQuoted, pastDue, awarded), 0, 100, 4, 1));
            when(platform.rfqs(any())).thenReturn(List.of(quoted, awarded));

            var result = badges.vendor();

            assertThat(result.openToQuote()).isEqualTo(1); // only notQuoted: pastDue is closed, the others are quoted
            assertThat(result.decidedQuotationIds()).containsExactly(41L);
        }
    }

    @Nested
    class AwardSummary {
        private final PlatformClient platform = mock(PlatformClient.class);
        private final AwardSummaryServiceImpl service = new AwardSummaryServiceImpl(platform, CLOCK);

        private QuotationColumn column(long quotationId, String vendor, int rank, String total) {
            return new QuotationColumn(quotationId, 0L, quotationId + 100, vendor, null, QuotationStatus.READY_FOR_COMPARISON,
                    "INR", null, null, null, new BigDecimal(total), new BigDecimal(total), null, false, null, null, null,
                    1, 0, true, rank, null, null, 0, List.of(), null);
        }

        @Test
        void thePdfShowsWhatWasAwardedToWhomAndWhy() throws Exception {
            PurchaseRequestResponse pr = rfq(1, PurchaseRequestStatus.APPROVED, null);
            QuotationItemResponse line = new QuotationItemResponse(1L, 1, 9L, 1, null, "Laptop Dell Latitude 5440",
                    new BigDecimal("10"), "Nos", new BigDecimal("68500"), null, null, new BigDecimal("18"), null,
                    new BigDecimal("808300.00"), null, null, null, false, null);
            QuotationResponse winner = new QuotationResponse(31L, 1L, pr.requestNumber(), 131L, "Vertex Auto",
                    "VA/QTN/42", null, LocalDate.of(2026, 12, 1), "INR", QuotationStatus.READY_FOR_COMPARISON,
                    new BigDecimal("685000.00"), BigDecimal.ZERO, new BigDecimal("123300.00"), new BigDecimal("808300.00"),
                    null, null, null, "2 weeks", "30 days", null, null, ExtractionSource.GROQ, null, null, 1, null,
                    "buyer", true, 0L, false, List.of(), List.of(line), List.of(), Instant.now(), Instant.now());
            ComparisonResult result = new ComparisonResult(1L, "INR",
                    List.of(column(31L, "Vertex Auto", 1, "808300.00"), column(32L, "Shakti Metals", 2, "915000.00")),
                    List.of(), List.of(), List.of(), 31L, ComparisonResult.DISCLAIMER);
            when(platform.rfq(1L)).thenReturn(pr);
            when(platform.latestDecision(1L)).thenReturn(Optional.of(new ApprovalResponse(5L, 1L, 20L, 2, 31L,
                    "Vertex Auto", ApprovalDecision.APPROVED, "Lowest complete quotation", "kailash-manager",
                    Instant.parse("2026-11-01T06:30:00Z"))));
            when(platform.quotation(31L)).thenReturn(winner);
            when(platform.comparison(20L)).thenReturn(new ComparisonReportResponse(20L, 1L, 2, "buyer", Instant.now(),
                    31L, true, result));

            var summary = service.summary(1L);

            assertThat(summary.fileName()).isEqualTo("PR-2026-00001-award-summary.pdf");
            String text;
            try (var doc = Loader.loadPDF(summary.pdf())) {
                text = new PDFTextStripper().getText(doc);
            }
            assertThat(text).contains("Decision: AWARDED", "Vertex Auto", "VA/QTN/42", "kailash-manager",
                    "Lowest complete quotation", "INR 8,08,300.00", "Shakti Metals", "Comparison version 2");
        }

        @Test
        void noDecisionNoSummary() {
            when(platform.rfq(1L)).thenReturn(rfq(1, PurchaseRequestStatus.UNDER_REVIEW, null));
            when(platform.latestDecision(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.summary(1L))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("NO_DECISION");
        }
    }
}
