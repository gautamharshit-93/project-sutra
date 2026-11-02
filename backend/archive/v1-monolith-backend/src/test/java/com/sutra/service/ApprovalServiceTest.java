package com.sutra.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.entity.AppUser;
import com.sutra.entity.ComparisonQuotation;
import com.sutra.entity.ComparisonReport;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.Quotation;
import com.sutra.entity.enums.ApprovalDecision;
import com.sutra.entity.enums.PurchaseRequestStatus;
import com.sutra.entity.enums.QuotationStatus;
import com.sutra.entity.enums.RoleName;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.ForbiddenOperationException;
import com.sutra.repository.ApprovalRepository;
import com.sutra.request.QuotationRequests.ApprovalRequest;
import com.sutra.security.CurrentUserProvider;
import com.sutra.support.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalServiceTest {

    @Mock ApprovalRepository approvalRepository;
    @Mock PurchaseRequestService purchaseRequestService;
    @Mock ComparisonService comparisonService;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;

    private ApprovalService service;
    private AppUser buyer;
    private AppUser approver;
    private PurchaseRequest pr;
    private ComparisonReport report;
    private Quotation cheapest;
    private Quotation dearer;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(approvalRepository, purchaseRequestService, comparisonService, currentUser,
                auditService, Fixtures.CLOCK);
        buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        approver = Fixtures.user("manager", RoleName.APPROVER);
        actAs(approver);

        pr = Fixtures.purchaseRequest(buyer);
        pr.setStatus(PurchaseRequestStatus.UNDER_REVIEW);
        cheapest = readyQuotation("Bravo");
        dearer = readyQuotation("Alpha");
        report = new ComparisonReport();
        report.setId(70L);
        report.setReportVersion(1);
        report.setPurchaseRequest(pr);
        report.setLowestCompleteQuotation(cheapest);
        addToReport(cheapest);
        addToReport(dearer);

        when(purchaseRequestService.find(pr.getId())).thenReturn(pr);
        when(comparisonService.find(70L)).thenReturn(report);
        when(comparisonService.latestFor(pr.getId())).thenReturn(Optional.of(report));
        when(comparisonService.isStale(report)).thenReturn(false);
        when(approvalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void actAs(AppUser user) {
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(user));
        when(currentUser.requireUser()).thenReturn(user);
    }

    private Quotation readyQuotation(String vendor) {
        Quotation q = Fixtures.quotation(pr, Fixtures.vendor(vendor));
        q.setStatus(QuotationStatus.READY_FOR_COMPARISON);
        return q;
    }

    private void addToReport(Quotation q) {
        ComparisonQuotation cq = new ComparisonQuotation();
        cq.setComparisonReport(report);
        cq.setQuotation(q);
        cq.setQuotationVersion(0L);
        report.getQuotations().add(cq);
    }

    @Test
    void approverRecordsApprovalOfTheLowestQuotation() {
        var response = service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.APPROVED, cheapest.getId(), null));

        assertThat(response.decision()).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(response.selectedQuotationId()).isEqualTo(cheapest.getId());
        assertThat(response.decidedBy()).isEqualTo("manager");
        assertThat(response.decidedAt()).isNotNull();
        assertThat(pr.getStatus()).isEqualTo(PurchaseRequestStatus.APPROVED);
    }

    @Test
    void choosingANonLowestQuotationRequiresAReason() {
        assertThatThrownBy(() -> service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.APPROVED, dearer.getId(), null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("REASON_REQUIRED");

        var response = service.decide(pr.getId(), new ApprovalRequest(70L, ApprovalDecision.APPROVED, dearer.getId(),
                "Better warranty and faster delivery"));
        assertThat(response.reason()).isEqualTo("Better warranty and faster delivery");
    }

    @Test
    void rejectionClosesTheRequest() {
        var response = service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.REJECTED, null, "Budget withdrawn"));

        assertThat(response.decision()).isEqualTo(ApprovalDecision.REJECTED);
        assertThat(pr.getStatus()).isEqualTo(PurchaseRequestStatus.REJECTED);
    }

    @Test
    void procurementUserCannotApprove() {
        actAs(Fixtures.user("other-buyer", RoleName.PROCUREMENT));

        assertThatThrownBy(() -> service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.APPROVED, cheapest.getId(), null)))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void approverCannotApproveTheirOwnRequest() {
        pr.setRequestedBy(approver);

        assertThatThrownBy(() -> service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.APPROVED, cheapest.getId(), null)))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("you created");
    }

    @Test
    void outdatedOrStaleComparisonCannotBeApproved() {
        ComparisonReport newer = new ComparisonReport();
        newer.setId(71L);
        newer.setReportVersion(2);
        when(comparisonService.latestFor(pr.getId())).thenReturn(Optional.of(newer));
        assertThatThrownBy(() -> service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.APPROVED, cheapest.getId(), null)))
                .extracting("code").isEqualTo("REPORT_OUTDATED");

        when(comparisonService.latestFor(pr.getId())).thenReturn(Optional.of(report));
        when(comparisonService.isStale(report)).thenReturn(true);
        assertThatThrownBy(() -> service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.APPROVED, cheapest.getId(), null)))
                .extracting("code").isEqualTo("REPORT_STALE");
    }

    @Test
    void quotationOutsideTheReportCannotBeSelected() {
        Quotation other = readyQuotation("Outsider");

        assertThatThrownBy(() -> service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.APPROVED, other.getId(), "reason")))
                .extracting("code").isEqualTo("QUOTATION_NOT_IN_REPORT");
    }

    @Test
    void decisionNeedsRequestUnderReview() {
        pr.setStatus(PurchaseRequestStatus.APPROVED);

        assertThatThrownBy(() -> service.decide(pr.getId(),
                new ApprovalRequest(70L, ApprovalDecision.REJECTED, null, "again")))
                .extracting("code").isEqualTo("PR_NOT_UNDER_REVIEW");
    }
}
