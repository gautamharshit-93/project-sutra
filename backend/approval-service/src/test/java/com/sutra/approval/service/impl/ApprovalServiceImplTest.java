package com.sutra.approval.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.comparison.ComparisonResult.QuotationColumn;
import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.enums.ApprovalDecision;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.quotation.QuotationRequests.ApprovalRequest;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.approval.client.ProcurementClients;
import com.sutra.approval.entity.Approval;
import com.sutra.approval.repository.ApprovalRepository;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Award and rejection rules; the RFQ, comparison and quotations come from their services. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC);
    private static final long RFQ = 10L;
    private static final long REPORT = 20L;
    private static final long CHEAPEST = 31L;
    private static final long DEARER = 32L;
    private static final long OUTSIDE = 33L;

    @Mock ApprovalRepository approvalRepository;
    @Mock ProcurementClients procurement;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;

    private ApprovalServiceImpl service;
    private final SutraUserPrincipal buyer = principal(1L, "buyer", RoleName.PROCUREMENT);
    private final SutraUserPrincipal manager = principal(2L, "manager", RoleName.APPROVER);
    private PurchaseRequestStatus rfqStatus;

    private static SutraUserPrincipal principal(Long id, String name, RoleName role) {
        return new SutraUserPrincipal(id, name, null, true, Set.of(role));
    }

    @BeforeEach
    void setUp() {
        service = new ApprovalServiceImpl(approvalRepository, procurement, currentUser, auditService, CLOCK);
        rfqStatus = PurchaseRequestStatus.UNDER_REVIEW;
        when(currentUser.requirePrincipal()).thenReturn(manager);
        when(procurement.rfq(RFQ)).thenAnswer(inv -> rfq(rfqStatus));
        when(procurement.report(REPORT)).thenReturn(report(REPORT, 1));
        when(procurement.latestReport(RFQ)).thenReturn(Optional.of(report(REPORT, 1)));
        when(procurement.quotation(anyLong())).thenAnswer(inv -> quotation(inv.getArgument(0)));
        when(approvalRepository.saveAndFlush(any())).thenAnswer(inv -> {
            Approval a = inv.getArgument(0);
            a.setId(99L);
            return a;
        });
    }

    private PurchaseRequestResponse rfq(PurchaseRequestStatus status) {
        return new PurchaseRequestResponse(RFQ, "PR-2026-00010", "Bearings", null, status, 1L, "Head Office", null,
                null, null, null, buyer.getId(), "buyer", "INR", null, true, List.of(), Instant.now(), Instant.now(), null);
    }

    private static ComparisonReportResponse report(long id, int version) {
        List<QuotationColumn> columns = List.of(column(CHEAPEST), column(DEARER));
        ComparisonResult result = new ComparisonResult(RFQ, "INR", columns, List.of(), List.of(), List.of(), CHEAPEST, "advisory");
        return new ComparisonReportResponse(id, RFQ, version, "buyer", Instant.now(), CHEAPEST, true, result);
    }

    private static QuotationColumn column(long quotationId) {
        return new QuotationColumn(quotationId, 0L, quotationId + 100, "Vendor " + quotationId, null,
                QuotationStatus.READY_FOR_COMPARISON, "INR", null, null, null, null, null, null, false, null, null, null,
                2, 0, true, null, null, null, 0, List.of(), null);
    }

    private static QuotationResponse quotation(long id) {
        return new QuotationResponse(id, RFQ, "PR-2026-00010", id + 100, "Vendor " + id, null, null,
                LocalDate.of(2026, 12, 1), "INR", QuotationStatus.READY_FOR_COMPARISON, null, null, null, null, null,
                null, null, null, null, null, null, ExtractionSource.GROQ, null, null, 1, null, "buyer", false, 0L, true,
                List.of(), List.of(), List.of(), Instant.now(), Instant.now());
    }

    private static ApprovalRequest approve(long quotationId, String reason) {
        return new ApprovalRequest(REPORT, ApprovalDecision.APPROVED, quotationId, reason);
    }

    @Test
    void approverRecordsApprovalOfTheLowestQuotation() {
        var response = service.decide(RFQ, approve(CHEAPEST, null));

        assertThat(response.decision()).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(response.selectedQuotationId()).isEqualTo(CHEAPEST);
        assertThat(response.selectedVendorName()).isEqualTo("Vendor 31");
        assertThat(response.comparisonReportVersion()).isEqualTo(1);
        assertThat(response.decidedBy()).isEqualTo("manager");
        verify(procurement).changeStatus(eq(RFQ), eq(PurchaseRequestStatus.UNDER_REVIEW),
                eq(PurchaseRequestStatus.APPROVED), eq("manager"), anyMap());
    }

    @Test
    void choosingANonLowestQuotationRequiresAReason() {
        assertThatThrownBy(() -> service.decide(RFQ, approve(DEARER, null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("REASON_REQUIRED");

        var response = service.decide(RFQ, approve(DEARER, "Better warranty and faster delivery"));
        assertThat(response.reason()).isEqualTo("Better warranty and faster delivery");
    }

    @Test
    void rejectionClosesTheRequest() {
        var response = service.decide(RFQ, new ApprovalRequest(REPORT, ApprovalDecision.REJECTED, null, "Prices too high"));

        assertThat(response.decision()).isEqualTo(ApprovalDecision.REJECTED);
        verify(procurement).changeStatus(eq(RFQ), eq(PurchaseRequestStatus.UNDER_REVIEW),
                eq(PurchaseRequestStatus.REJECTED), eq("manager"), anyMap());
    }

    @Test
    void procurementUserCannotApprove() {
        when(currentUser.requirePrincipal()).thenReturn(buyer);

        assertThatThrownBy(() -> service.decide(RFQ, approve(CHEAPEST, null)))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(approvalRepository, never()).saveAndFlush(any());
    }

    @Test
    void approverCannotApproveTheirOwnRequest() {
        SutraUserPrincipal managerWhoRaisedIt = principal(buyer.getId(), "buyer", RoleName.APPROVER);
        when(currentUser.requirePrincipal()).thenReturn(managerWhoRaisedIt);

        assertThatThrownBy(() -> service.decide(RFQ, approve(CHEAPEST, null)))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("you created");
    }

    @Test
    void outdatedOrStaleComparisonCannotBeApproved() {
        when(procurement.latestReport(RFQ)).thenReturn(Optional.of(report(21L, 2)));
        assertThatThrownBy(() -> service.decide(RFQ, approve(CHEAPEST, null)))
                .extracting("code").isEqualTo("REPORT_OUTDATED");

        when(procurement.latestReport(RFQ)).thenReturn(Optional.of(report(REPORT, 1)));
        when(procurement.isStale(REPORT)).thenReturn(true);
        assertThatThrownBy(() -> service.decide(RFQ, approve(CHEAPEST, null)))
                .extracting("code").isEqualTo("REPORT_STALE");
    }

    @Test
    void quotationOutsideTheReportCannotBeSelected() {
        assertThatThrownBy(() -> service.decide(RFQ, approve(OUTSIDE, "other")))
                .extracting("code").isEqualTo("QUOTATION_NOT_IN_REPORT");
    }

    @Test
    void aQuotationNoLongerReadyCannotBeSelected() {
        QuotationResponse changed = quotation(CHEAPEST);
        when(procurement.quotation(CHEAPEST)).thenReturn(new QuotationResponse(changed.id(), changed.purchaseRequestId(),
                changed.requestNumber(), changed.vendorId(), changed.vendorName(), null, null, changed.validUntil(), "INR",
                QuotationStatus.NEEDS_REVIEW, null, null, null, null, null, null, null, null, null, null, null,
                ExtractionSource.GROQ, null, null, 1, null, "buyer", false, 1L, true, List.of(), List.of(), List.of(),
                Instant.now(), Instant.now()));

        assertThatThrownBy(() -> service.decide(RFQ, approve(CHEAPEST, null)))
                .extracting("code").isEqualTo("QUOTATION_NOT_READY");
    }

    @Test
    void decisionNeedsRequestUnderReview() {
        rfqStatus = PurchaseRequestStatus.OPEN;

        assertThatThrownBy(() -> service.decide(RFQ, approve(CHEAPEST, null)))
                .extracting("code").isEqualTo("PR_NOT_UNDER_REVIEW");
    }
}
