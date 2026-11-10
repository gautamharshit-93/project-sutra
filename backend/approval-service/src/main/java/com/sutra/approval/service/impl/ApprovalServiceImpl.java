package com.sutra.approval.service.impl;

import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.enums.ApprovalDecision;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.quotation.QuotationRequests.ApprovalRequest;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.approval.client.ProcurementClients;
import com.sutra.approval.entity.Approval;
import com.sutra.approval.entity.UserRef;
import com.sutra.approval.repository.ApprovalRepository;
import com.sutra.approval.service.ApprovalService;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.sutra.common.audit.AuditService.details;

/**
 * Records human approval or rejection decisions. Nothing in the system calls this automatically: a vendor is only
 * ever selected by an authorised user here. The decision is saved, then the RFQ is moved to APPROVED or REJECTED in
 * the same transaction (if the RFQ service refuses, the decision is not kept).
 */
@Service
@Transactional
public class ApprovalServiceImpl implements ApprovalService {

    static final String AUDIT_TYPE = "APPROVAL";

    private final ApprovalRepository approvalRepository;
    private final ProcurementClients procurement;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final Clock clock;

    public ApprovalServiceImpl(ApprovalRepository approvalRepository, ProcurementClients procurement,
                               CurrentUserProvider currentUser, AuditService auditService, Clock clock) {
        this.approvalRepository = approvalRepository;
        this.procurement = procurement;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalResponse> list(Long purchaseRequestId) {
        procurement.rfq(purchaseRequestId); // 404 for an unknown RFQ
        return approvalRepository.findByPurchaseRequestIdOrderByDecidedAtDesc(purchaseRequestId).stream()
                .map(ApprovalServiceImpl::toResponse).toList();
    }

    @Override
    public ApprovalResponse decide(Long purchaseRequestId, ApprovalRequest request) {
        SutraUserPrincipal principal = currentUser.requirePrincipal();
        if (!principal.hasRole(RoleName.APPROVER) && !principal.hasRole(RoleName.ADMIN)) {
            throw new ForbiddenOperationException("Only approvers can record approval decisions");
        }
        PurchaseRequestResponse pr = procurement.rfq(purchaseRequestId);
        if (!principal.hasRole(RoleName.ADMIN) && pr.requestedById().equals(principal.getId())) {
            throw new ForbiddenOperationException("You cannot approve a purchase request you created");
        }
        if (pr.status() != PurchaseRequestStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("PR_NOT_UNDER_REVIEW", pr.requestNumber() + " is " + pr.status()
                    + "; a decision can only be recorded while it is UNDER_REVIEW");
        }
        ComparisonReportResponse report = procurement.report(request.comparisonReportId());
        if (!report.purchaseRequestId().equals(purchaseRequestId)) {
            throw new BusinessRuleException("REPORT_MISMATCH", "The comparison report belongs to another request");
        }
        ComparisonReportResponse latest = procurement.latestReport(purchaseRequestId).orElseThrow();
        if (!latest.id().equals(report.id())) {
            throw new BusinessRuleException("REPORT_OUTDATED",
                    "A newer comparison (version " + latest.reportVersion() + ") exists; review that one instead");
        }
        if (procurement.isStale(report.id())) {
            throw new BusinessRuleException("REPORT_STALE",
                    "Quotations changed after this comparison was generated; regenerate the comparison first");
        }
        Approval approval = new Approval();
        approval.setPurchaseRequestId(purchaseRequestId);
        approval.setComparisonReportId(report.id());
        approval.setComparisonReportVersion(report.reportVersion());
        approval.setDecision(request.decision());
        approval.setReason(request.reason() == null || request.reason().isBlank() ? null : request.reason().trim());
        approval.setDecidedBy(UserRef.of(principal));
        approval.setDecidedAt(Instant.now(clock));
        PurchaseRequestStatus target;
        if (request.decision() == ApprovalDecision.APPROVED) {
            QuotationResponse selected = selectedQuotation(report, request.selectedQuotationId());
            boolean notLowest = !Objects.equals(selected.id(), report.lowestCompleteQuotationId());
            boolean expired = selected.validUntil() != null && selected.validUntil().isBefore(LocalDate.now(clock));
            if ((notLowest || expired) && approval.getReason() == null) {
                throw new BusinessRuleException("REASON_REQUIRED", notLowest
                        ? "A reason is required when the selected quotation is not the lowest complete quotation"
                        : "A reason is required when approving an expired quotation");
            }
            approval.setSelectedQuotationId(selected.id());
            approval.setSelectedVendorName(selected.vendorName());
            target = PurchaseRequestStatus.APPROVED;
        } else {
            target = PurchaseRequestStatus.REJECTED;
        }
        approval = approvalRepository.saveAndFlush(approval); // constraints checked before the RFQ is moved
        auditService.record(AUDIT_TYPE, approval.getId(), request.decision().name(), details(
                "purchaseRequest", pr.requestNumber(), "comparisonVersion", report.reportVersion(),
                "selectedQuotationId", request.selectedQuotationId(), "reason", approval.getReason()));
        procurement.changeStatus(purchaseRequestId, PurchaseRequestStatus.UNDER_REVIEW, target, principal.getUsername(),
                details("approvalId", approval.getId()));
        return toResponse(approval);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalResponse> latestFor(Collection<Long> rfqIds) {
        return rfqIds.isEmpty() ? List.of()
                : approvalRepository.findLatestFor(rfqIds).stream().map(ApprovalServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long decisionsBy(Long userId) {
        return approvalRepository.countByDecidedById(userId);
    }

    /** The selected quotation must be part of the report and still ready for comparison. */
    private QuotationResponse selectedQuotation(ComparisonReportResponse report, Long quotationId) {
        ComparisonResult result = report.result();
        boolean inReport = result != null && result.quotations().stream()
                .anyMatch(c -> c.quotationId().equals(quotationId));
        if (!inReport) {
            throw new BusinessRuleException("QUOTATION_NOT_IN_REPORT", "The selected quotation is not part of this comparison");
        }
        QuotationResponse selected = procurement.quotation(quotationId);
        if (selected.status() != QuotationStatus.READY_FOR_COMPARISON) {
            throw new BusinessRuleException("QUOTATION_NOT_READY", "The selected quotation is no longer ready");
        }
        return selected;
    }

    static ApprovalResponse toResponse(Approval a) {
        return new ApprovalResponse(a.getId(), a.getPurchaseRequestId(), a.getComparisonReportId(),
                a.getComparisonReportVersion(), a.getSelectedQuotationId(), a.getSelectedVendorName(), a.getDecision(),
                a.getReason(), a.getDecidedBy().getUsername(), a.getDecidedAt());
    }
}
