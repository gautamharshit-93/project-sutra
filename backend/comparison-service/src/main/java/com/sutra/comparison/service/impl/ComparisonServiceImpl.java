package com.sutra.comparison.service.impl;

import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.comparison.client.ProcurementClients;
import com.sutra.comparison.entity.ComparisonQuotation;
import com.sutra.comparison.entity.ComparisonReport;
import com.sutra.comparison.entity.UserRef;
import com.sutra.comparison.repository.ComparisonReportRepository;
import com.sutra.comparison.service.ComparisonService;
import com.sutra.comparison.service.internal.ComparisonEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.sutra.common.audit.AuditService.details;

/**
 * Generates and stores versioned side-by-side comparisons of READY_FOR_COMPARISON quotations. The RFQ and the
 * quotations are read from their services; the report is saved here, then the RFQ is moved to UNDER_REVIEW in the
 * same transaction (if the RFQ service refuses, the report is not kept).
 */
@Service
@Transactional
public class ComparisonServiceImpl implements ComparisonService {

    static final String AUDIT_TYPE = "COMPARISON_REPORT";

    private final ComparisonReportRepository reportRepository;
    private final ProcurementClients procurement;
    private final ComparisonEngine engine;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ComparisonServiceImpl(ComparisonReportRepository reportRepository, ProcurementClients procurement,
                                 ComparisonEngine engine, CurrentUserProvider currentUser, AuditService auditService,
                                 ObjectMapper objectMapper, Clock clock) {
        this.reportRepository = reportRepository;
        this.procurement = procurement;
        this.engine = engine;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public ComparisonReportResponse generate(Long purchaseRequestId) {
        PurchaseRequestResponse pr = procurement.rfq(purchaseRequestId);
        requireModify(pr);
        if (pr.status() != PurchaseRequestStatus.OPEN && pr.status() != PurchaseRequestStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("PR_NOT_COMPARABLE",
                    "Comparisons can only be generated while the request is OPEN or UNDER_REVIEW");
        }
        List<QuotationResponse> all = procurement.quotations(purchaseRequestId);
        List<QuotationResponse> ready = all.stream().filter(q -> q.status() == QuotationStatus.READY_FOR_COMPARISON).toList();
        if (ready.isEmpty()) {
            throw new BusinessRuleException("NO_READY_QUOTATIONS",
                    "No quotation is ready for comparison yet. Wait until the quotations have been read, and fix any line "
                            + "without a quantity or price.");
        }
        ComparisonResult computed = engine.compare(pr, ready);
        List<String> warnings = new ArrayList<>(computed.warnings());
        long excluded = all.size() - ready.size();
        if (excluded > 0) {
            warnings.add(0, excluded + " quotation(s) not ready for comparison were excluded");
        }
        if (ready.size() == 1) {
            warnings.add(0, "Only one quotation is ready; there is nothing to compare it against yet");
        }
        ComparisonResult result = new ComparisonResult(computed.purchaseRequestId(), computed.requestCurrency(),
                computed.quotations(), computed.rows(), computed.unmatchedItems(), warnings,
                computed.lowestCompleteQuotationId(), computed.disclaimer());

        ComparisonReport report = new ComparisonReport();
        report.setPurchaseRequestId(purchaseRequestId);
        report.setReportVersion(reportRepository.findMaxVersion(purchaseRequestId) + 1);
        report.setGeneratedBy(UserRef.of(currentUser.requirePrincipal()));
        report.setGeneratedAt(Instant.now(clock));
        report.setDetails(toJson(result));
        report.setLowestCompleteQuotationId(result.lowestCompleteQuotationId());
        for (ComparisonResult.QuotationColumn column : result.quotations()) {
            ComparisonQuotation cq = new ComparisonQuotation();
            cq.setComparisonReport(report);
            cq.setQuotationId(column.quotationId());
            cq.setQuotationVersion(column.quotationVersion());
            cq.setGrandTotal(column.grandTotal());
            cq.setItemsQuoted(column.itemsQuoted());
            cq.setItemsMissing(column.itemsMissing());
            cq.setPriceRank(column.priceRank());
            report.getQuotations().add(cq);
        }
        report = reportRepository.saveAndFlush(report); // constraints checked before the RFQ is moved
        Set<Long> compared = ready.stream().map(QuotationResponse::id).collect(Collectors.toSet());
        auditService.record(AUDIT_TYPE, report.getId(), "GENERATED", details("purchaseRequest", pr.requestNumber(),
                "version", report.getReportVersion(), "quotations", compared,
                "lowestCompleteQuotationId", result.lowestCompleteQuotationId()));
        if (pr.status() == PurchaseRequestStatus.OPEN) {
            procurement.changeStatus(purchaseRequestId, PurchaseRequestStatus.OPEN, PurchaseRequestStatus.UNDER_REVIEW,
                    currentUser.username(), details("comparisonVersion", report.getReportVersion()));
        }
        return toResponse(report, result, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComparisonReportResponse> list(Long purchaseRequestId) {
        procurement.rfq(purchaseRequestId); // 404 for an unknown RFQ
        List<ComparisonReport> reports = reportRepository.findByPurchaseRequestIdOrderByReportVersionDesc(purchaseRequestId);
        List<ComparisonReportResponse> responses = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            responses.add(toResponse(reports.get(i), fromJson(reports.get(i).getDetails()), i == 0));
        }
        return responses;
    }

    @Override
    @Transactional(readOnly = true)
    public ComparisonReportResponse get(Long reportId) {
        ComparisonReport report = find(reportId);
        boolean latest = reportRepository.findFirstByPurchaseRequestIdOrderByReportVersionDesc(report.getPurchaseRequestId())
                .map(r -> r.getId().equals(reportId)).orElse(false);
        return toResponse(report, fromJson(report.getDetails()), latest);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComparisonReportResponse> latest(Long purchaseRequestId) {
        return reportRepository.findFirstByPurchaseRequestIdOrderByReportVersionDesc(purchaseRequestId)
                .map(r -> toResponse(r, fromJson(r.getDetails()), true));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isStale(Long reportId) {
        ComparisonReport report = find(reportId);
        Set<Long> ids = report.getQuotations().stream().map(ComparisonQuotation::getQuotationId).collect(Collectors.toSet());
        Map<Long, Long> current = procurement.versions(ids);
        return report.getQuotations().stream()
                .anyMatch(cq -> !cq.getQuotationVersion().equals(current.get(cq.getQuotationId())));
    }

    @Override
    @Transactional(readOnly = true)
    public long references(Long quotationId) {
        return reportRepository.countQuotationReferences(quotationId);
    }

    private ComparisonReport find(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Comparison report", reportId));
    }

    /** Administrators may compare any RFQ; buyers only the RFQs they raised. */
    private void requireModify(PurchaseRequestResponse pr) {
        SutraUserPrincipal principal = currentUser.requirePrincipal();
        boolean allowed = principal.hasRole(RoleName.ADMIN)
                || (principal.hasRole(RoleName.PROCUREMENT) && pr.requestedById().equals(principal.getId()));
        if (!allowed) {
            throw new ForbiddenOperationException("Only the creator of " + pr.requestNumber()
                    + " or an administrator can change it or its quotations");
        }
    }

    static ComparisonReportResponse toResponse(ComparisonReport r, ComparisonResult result, boolean latest) {
        return new ComparisonReportResponse(r.getId(), r.getPurchaseRequestId(), r.getReportVersion(),
                r.getGeneratedBy().getUsername(), r.getGeneratedAt(), r.getLowestCompleteQuotationId(), latest, result);
    }

    private ComparisonResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, ComparisonResult.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored comparison report could not be read", e);
        }
    }

    private String toJson(ComparisonResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException e) {
            throw new IllegalStateException("Comparison could not be serialised", e);
        }
    }
}
