package com.sutra.procurement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.procurement.client.MasterDataClient;
import com.sutra.procurement.entity.Approval;
import com.sutra.procurement.entity.ComparisonQuotation;
import com.sutra.procurement.entity.ComparisonReport;
import com.sutra.procurement.entity.NamedRef;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.UserRef;
import com.sutra.procurement.entity.enums.ApprovalDecision;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.repository.ApprovalRepository;
import com.sutra.procurement.repository.PurchaseRequestRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.request.PurchaseRequestRequests.NewRfqRequest;
import com.sutra.procurement.request.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.procurement.request.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.procurement.security.RecordAccessPolicy;
import com.sutra.procurement.support.Fixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/** One-step RFQ creation, quotation due dates, menu badges and the award summary PDF. */
class NewFeaturesServiceTest {

    @Nested
    class CreateWithItems {
        private final PurchaseRequestRepository repository = mock(PurchaseRequestRepository.class);
        private final QuotationRepository quotations = mock(QuotationRepository.class);
        private final MasterDataClient masterData = mock(MasterDataClient.class);
        private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        private final PurchaseRequestService service = new PurchaseRequestService(repository, quotations, masterData,
                mock(QuotationReviewService.class), currentUser, new RecordAccessPolicy(currentUser),
                mock(AuditService.class), Fixtures.CLOCK);

        private void buyerCreates() {
            when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(Fixtures.user("buyer", RoleName.PROCUREMENT)));
            when(masterData.entity(5L)).thenReturn(new MasterDataClient.EntitySnapshot(5L, "AXM", "Axiom", true));
            when(repository.nextRequestNumber()).thenReturn(7L);
            AtomicReference<PurchaseRequest> saved = new AtomicReference<>();
            when(repository.save(any())).thenAnswer(inv -> {
                PurchaseRequest p = inv.getArgument(0);
                p.setId(70L);
                saved.set(p);
                return p;
            });
            when(repository.findById(70L)).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        }

        private static PurchaseRequestRequest header(LocalDate requiredBy, LocalDate due) {
            return new PurchaseRequestRequest("Steel for Q1", null, 5L, null, null, "INR", requiredBy, due);
        }

        private static RequestItemRequest line(String code, String qty) {
            return new RequestItemRequest(code, "CRCA sheet " + code, new BigDecimal(qty), "Tonne", null);
        }

        @Test
        void headerItemsAndOpeningHappenInOneCall() {
            buyerCreates();

            var pr = service.createWithItems(new NewRfqRequest(header(LocalDate.of(2026, 12, 15), LocalDate.of(2026, 11, 30)),
                    List.of(line("MT-101", "12"), line("MT-102", "8")), true));

            assertThat(pr.requestNumber()).isEqualTo("PR-2026-00007");
            assertThat(pr.items()).hasSize(2);
            assertThat(pr.status()).isEqualTo(PurchaseRequestStatus.OPEN);
            assertThat(pr.quotationsDue()).isEqualTo(LocalDate.of(2026, 11, 30));
        }

        @Test
        void withoutOpeningItStaysADraft() {
            buyerCreates();

            var pr = service.createWithItems(new NewRfqRequest(header(null, null), List.of(line("MT-101", "1")), false));

            assertThat(pr.status()).isEqualTo(PurchaseRequestStatus.DRAFT);
        }

        @Test
        void quotationsCannotBeDueAfterTheGoodsAreNeeded() {
            buyerCreates();

            assertThatThrownBy(() -> service.create(header(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25))))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("DUE_AFTER_REQUIRED_BY");
        }
    }

    @Nested
    class DueDate {
        @Test
        void vendorsMayQuoteUpToAndIncludingTheDueDate() {
            PurchaseRequest pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
            pr.setQuotationsDue(LocalDate.of(2026, 11, 30));

            assertThat(pr.acceptsVendorQuotations(LocalDate.of(2026, 11, 30))).isTrue();
            assertThat(pr.acceptsVendorQuotations(LocalDate.of(2026, 12, 1))).isFalse();
            pr.setQuotationsDue(null);
            assertThat(pr.acceptsVendorQuotations(LocalDate.of(2030, 1, 1))).as("no due date: open until compared").isTrue();
            pr.setStatus(PurchaseRequestStatus.UNDER_REVIEW);
            assertThat(pr.acceptsVendorQuotations(LocalDate.of(2026, 11, 1))).isFalse();
        }
    }

    @Nested
    class Badges {
        private final PurchaseRequestRepository repository = mock(PurchaseRequestRepository.class);
        private final QuotationRepository quotations = mock(QuotationRepository.class);
        private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        private final BadgeService badges = new BadgeService(repository, quotations, currentUser, Fixtures.CLOCK);

        private void as(SutraUserPrincipal user) {
            when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(user));
        }

        @Test
        void buyersSeeTheirOwnRfqsReadyToCompare() {
            SutraUserPrincipal buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
            as(buyer);
            when(repository.countReadyToCompare(buyer.getId())).thenReturn(2L);

            assertThat(badges.staff()).isEqualTo(new BadgeService.StaffBadges(2, 0));
            verify(repository, never()).countAwaitingAward(any());
        }

        @Test
        void managersSeeComparedRfqsWaitingForAnAwardExceptTheirOwn() {
            SutraUserPrincipal manager = Fixtures.user("manager", RoleName.APPROVER);
            as(manager);
            when(repository.countAwaitingAward(manager.getId())).thenReturn(3L);

            assertThat(badges.staff()).isEqualTo(new BadgeService.StaffBadges(0, 3));
        }

        @Test
        void administratorsSeeEverything() {
            as(Fixtures.user("admin", RoleName.ADMIN));
            when(repository.countReadyToCompare(isNull())).thenReturn(4L);
            when(repository.countAwaitingAward(isNull())).thenReturn(1L);

            assertThat(badges.staff()).isEqualTo(new BadgeService.StaffBadges(4, 1));
        }

        @Test
        void vendorsSeeOpenRfqsTheyHaveNotQuotedOnAndTheirDecidedQuotations() {
            NamedRef vendor = Fixtures.vendor("Brakeline");
            as(Fixtures.vendorUser("brakeline", vendor));
            SutraUserPrincipal buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
            PurchaseRequest quoted = Fixtures.purchaseRequest(buyer);
            PurchaseRequest notQuoted = Fixtures.purchaseRequest(buyer);
            PurchaseRequest pastDue = Fixtures.purchaseRequest(buyer);
            pastDue.setQuotationsDue(Fixtures.TODAY.minusDays(1));
            PurchaseRequest awarded = Fixtures.purchaseRequest(buyer);
            awarded.setStatus(PurchaseRequestStatus.APPROVED);
            Quotation mine = Fixtures.quotation(quoted, vendor);
            Quotation won = Fixtures.quotation(awarded, vendor);
            when(repository.visibleToVendor(any(), any())).thenReturn(new PageImpl<>(List.of(quoted, notQuoted, pastDue, awarded)));
            when(quotations.findByVendorIdOrderByIdDesc(vendor.getId())).thenReturn(List.of(won, mine));

            var result = badges.vendor();

            assertThat(result.openToQuote()).isEqualTo(1); // only notQuoted: pastDue is closed, the others are quoted
            assertThat(result.decidedQuotationIds()).containsExactly(won.getId());
        }
    }

    @Nested
    class AwardSummary {
        private final PurchaseRequestService purchaseRequests = mock(PurchaseRequestService.class);
        private final ApprovalRepository approvals = mock(ApprovalRepository.class);
        private final AwardSummaryService service = new AwardSummaryService(purchaseRequests, approvals, Fixtures.CLOCK);

        @Test
        void thePdfShowsWhatWasAwardedToWhomAndWhy() throws Exception {
            SutraUserPrincipal buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
            PurchaseRequest pr = Fixtures.purchaseRequest(buyer);
            pr.setStatus(PurchaseRequestStatus.APPROVED);
            Quotation winner = Fixtures.quotation(pr, Fixtures.vendor("Vertex Auto"));
            winner.setQuotationNumber("VA/QTN/42");
            Fixtures.item(winner, pr.getItems().get(0), "Laptop Dell Latitude 5440", "10", "68500", "18");
            winner.setGrandTotal(new BigDecimal("808300.00"));
            Quotation other = Fixtures.quotation(pr, Fixtures.vendor("Shakti Metals"));
            ComparisonReport report = new ComparisonReport();
            report.setReportVersion(2);
            report.setQuotations(List.of(row(report, winner, 1, "808300.00"), row(report, other, 2, "915000.00")));
            Approval approval = new Approval();
            approval.setPurchaseRequest(pr);
            approval.setComparisonReport(report);
            approval.setSelectedQuotation(winner);
            approval.setDecision(ApprovalDecision.APPROVED);
            approval.setReason("Lowest complete quotation");
            approval.setDecidedBy(new UserRef(9L, "kailash-manager"));
            approval.setDecidedAt(Instant.parse("2026-11-01T06:30:00Z"));
            when(purchaseRequests.find(pr.getId())).thenReturn(pr);
            when(approvals.findByPurchaseRequestIdOrderByDecidedAtDesc(pr.getId())).thenReturn(List.of(approval));

            var summary = service.summary(pr.getId());

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
            PurchaseRequest pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
            when(purchaseRequests.find(anyLong())).thenReturn(pr);
            when(approvals.findByPurchaseRequestIdOrderByDecidedAtDesc(anyLong())).thenReturn(List.of());

            assertThatThrownBy(() -> service.summary(pr.getId()))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("NO_DECISION");
        }

        private static ComparisonQuotation row(ComparisonReport report, Quotation q, int rank, String total) {
            ComparisonQuotation c = new ComparisonQuotation();
            c.setComparisonReport(report);
            c.setQuotation(q);
            c.setPriceRank(rank);
            c.setGrandTotal(new BigDecimal(total));
            c.setItemsQuoted(1);
            return c;
        }
    }
}
