package com.sutra.service;

import static com.sutra.service.AuditService.details;

import com.sutra.entity.Approval;
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
import com.sutra.response.DecisionResponses.ApprovalResponse;
import com.sutra.security.CurrentUserProvider;
import com.sutra.security.SutraUserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records human approval or rejection decisions. Nothing in the system calls this automatically:
 * a vendor is only ever selected by an authorised user through this service.
 */
@Service
@Transactional
public class ApprovalService {

    static final String AUDIT_TYPE = "APPROVAL";

    private final ApprovalRepository approvalRepository;
    private final PurchaseRequestService purchaseRequestService;
    private final ComparisonService comparisonService;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvalRepository, PurchaseRequestService purchaseRequestService,
                           ComparisonService comparisonService, CurrentUserProvider currentUser,
                           AuditService auditService, Clock clock) {
        this.approvalRepository = approvalRepository;
        this.purchaseRequestService = purchaseRequestService;
        this.comparisonService = comparisonService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ApprovalResponse> list(Long purchaseRequestId) {
        purchaseRequestService.find(purchaseRequestId);
        return approvalRepository.findByPurchaseRequestIdOrderByDecidedAtDesc(purchaseRequestId).stream()
                .map(ApprovalResponse::from).toList();
    }

    public ApprovalResponse decide(Long purchaseRequestId, ApprovalRequest request) {
        SutraUserPrincipal principal = currentUser.requirePrincipal();
        if (!principal.hasRole(RoleName.APPROVER) && !principal.hasRole(RoleName.ADMIN)) {
            throw new ForbiddenOperationException("Only approvers can record approval decisions");
        }
        PurchaseRequest pr = purchaseRequestService.find(purchaseRequestId);
        if (!principal.hasRole(RoleName.ADMIN) && pr.getRequestedBy().getId().equals(principal.getId())) {
            throw new ForbiddenOperationException("You cannot approve a purchase request you created");
        }
        if (pr.getStatus() != PurchaseRequestStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("PR_NOT_UNDER_REVIEW", pr.getRequestNumber() + " is " + pr.getStatus()
                    + "; a decision can only be recorded while it is UNDER_REVIEW");
        }

        ComparisonReport report = comparisonService.find(request.comparisonReportId());
        if (!report.getPurchaseRequest().getId().equals(purchaseRequestId)) {
            throw new BusinessRuleException("REPORT_MISMATCH", "The comparison report belongs to another request");
        }
        ComparisonReport latest = comparisonService.latestFor(purchaseRequestId).orElseThrow();
        if (!latest.getId().equals(report.getId())) {
            throw new BusinessRuleException("REPORT_OUTDATED",
                    "A newer comparison (version " + latest.getReportVersion() + ") exists; review that one instead");
        }
        if (comparisonService.isStale(report)) {
            throw new BusinessRuleException("REPORT_STALE",
                    "Quotations changed after this comparison was generated; regenerate the comparison first");
        }

        Approval approval = new Approval();
        approval.setPurchaseRequest(pr);
        approval.setComparisonReport(report);
        approval.setDecision(request.decision());
        approval.setReason(request.reason() == null || request.reason().isBlank() ? null : request.reason().trim());
        approval.setDecidedBy(currentUser.requireUser());
        approval.setDecidedAt(Instant.now(clock));

        if (request.decision() == ApprovalDecision.APPROVED) {
            Quotation selected = selectedQuotation(report, request.selectedQuotationId());
            boolean notLowest = !Objects.equals(selected.getId(),
                    report.getLowestCompleteQuotation() == null ? null : report.getLowestCompleteQuotation().getId());
            boolean expired = selected.getValidUntil() != null && selected.getValidUntil().isBefore(LocalDate.now(clock));
            if ((notLowest || expired) && approval.getReason() == null) {
                throw new BusinessRuleException("REASON_REQUIRED", notLowest
                        ? "A reason is required when the selected quotation is not the lowest complete quotation"
                        : "A reason is required when approving an expired quotation");
            }
            approval.setSelectedQuotation(selected);
            pr.setStatus(PurchaseRequestStatus.APPROVED);
        } else {
            pr.setStatus(PurchaseRequestStatus.REJECTED);
        }

        approval = approvalRepository.save(approval);
        auditService.record(AUDIT_TYPE, approval.getId(), request.decision().name(), details(
                "purchaseRequest", pr.getRequestNumber(), "comparisonVersion", report.getReportVersion(),
                "selectedQuotationId", request.selectedQuotationId(), "reason", approval.getReason()));
        auditService.record(PurchaseRequestService.AUDIT_TYPE, pr.getId(), "STATUS_CHANGED",
                details("from", PurchaseRequestStatus.UNDER_REVIEW, "to", pr.getStatus(), "approvalId", approval.getId()));
        return ApprovalResponse.from(approval);
    }

    private static Quotation selectedQuotation(ComparisonReport report, Long quotationId) {
        Quotation selected = report.getQuotations().stream()
                .map(ComparisonQuotation::getQuotation)
                .filter(q -> q.getId().equals(quotationId))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("QUOTATION_NOT_IN_REPORT",
                        "The selected quotation is not part of this comparison"));
        if (selected.getStatus() != QuotationStatus.READY_FOR_COMPARISON) {
            throw new BusinessRuleException("QUOTATION_NOT_READY", "The selected quotation is no longer ready");
        }
        return selected;
    }
}
