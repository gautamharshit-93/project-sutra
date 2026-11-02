package com.sutra.procurement.service;

import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sutra.procurement.calculation.ComparisonEngine;
import com.sutra.procurement.calculation.ComparisonResult;
import com.sutra.procurement.entity.ComparisonQuotation;
import com.sutra.procurement.entity.ComparisonReport;
import com.sutra.procurement.entity.UserRef;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.procurement.repository.ComparisonReportRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.response.DecisionResponses.ComparisonReportResponse;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.procurement.security.RecordAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Generates and stores versioned side-by-side comparisons of READY_FOR_COMPARISON quotations. */
@Service
@Transactional
public class ComparisonService {

    static final String AUDIT_TYPE = "COMPARISON_REPORT";

    private final ComparisonReportRepository reportRepository;
    private final QuotationRepository quotationRepository;
    private final PurchaseRequestService purchaseRequestService;
    private final ComparisonEngine engine;
    private final RecordAccessPolicy accessPolicy;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ComparisonService(ComparisonReportRepository reportRepository, QuotationRepository quotationRepository,
                             PurchaseRequestService purchaseRequestService, ComparisonEngine engine,
                             RecordAccessPolicy accessPolicy, CurrentUserProvider currentUser,
                             AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.reportRepository = reportRepository;
        this.quotationRepository = quotationRepository;
        this.purchaseRequestService = purchaseRequestService;
        this.engine = engine;
        this.accessPolicy = accessPolicy;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ComparisonReportResponse generate(Long purchaseRequestId) {
        PurchaseRequest pr = purchaseRequestService.find(purchaseRequestId);
        accessPolicy.requireModify(pr);
        if (pr.getStatus() != PurchaseRequestStatus.OPEN && pr.getStatus() != PurchaseRequestStatus.UNDER_REVIEW) {
            throw new BusinessRuleException("PR_NOT_COMPARABLE",
                    "Comparisons can only be generated while the request is OPEN or UNDER_REVIEW");
        }
        List<Quotation> all = quotationRepository.findByPurchaseRequestIdOrderByIdAsc(purchaseRequestId);
        List<Quotation> ready = all.stream().filter(q -> q.getStatus() == QuotationStatus.READY_FOR_COMPARISON).toList();
        if (ready.isEmpty()) {
            throw new BusinessRuleException("NO_READY_QUOTATIONS",
                    "No quotation is ready for comparison. Review the quotations and mark them ready first.");
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
        report.setPurchaseRequest(pr);
        report.setReportVersion(reportRepository.findMaxVersion(purchaseRequestId) + 1);
        report.setGeneratedBy(UserRef.of(currentUser.requirePrincipal()));
        report.setGeneratedAt(Instant.now(clock));
        report.setDetails(toJson(result));
        Map<Long, Quotation> byId = ready.stream().collect(Collectors.toMap(Quotation::getId, Function.identity()));
        report.setLowestCompleteQuotation(result.lowestCompleteQuotationId() == null ? null
                : byId.get(result.lowestCompleteQuotationId()));
        for (ComparisonResult.QuotationColumn column : result.quotations()) {
            ComparisonQuotation cq = new ComparisonQuotation();
            cq.setComparisonReport(report);
            cq.setQuotation(byId.get(column.quotationId()));
            cq.setQuotationVersion(column.quotationVersion());
            cq.setGrandTotal(column.grandTotal());
            cq.setItemsQuoted(column.itemsQuoted());
            cq.setItemsMissing(column.itemsMissing());
            cq.setPriceRank(column.priceRank());
            report.getQuotations().add(cq);
        }
        report = reportRepository.save(report);

        if (pr.getStatus() == PurchaseRequestStatus.OPEN) {
            pr.setStatus(PurchaseRequestStatus.UNDER_REVIEW);
        }
        auditService.record(AUDIT_TYPE, report.getId(), "GENERATED", details("purchaseRequest", pr.getRequestNumber(),
                "version", report.getReportVersion(), "quotations", byId.keySet(),
                "lowestCompleteQuotationId", result.lowestCompleteQuotationId()));
        return ComparisonReportResponse.from(report, result, true);
    }

    @Transactional(readOnly = true)
    public List<ComparisonReportResponse> list(Long purchaseRequestId) {
        purchaseRequestService.find(purchaseRequestId);
        List<ComparisonReport> reports = reportRepository.findByPurchaseRequestIdOrderByReportVersionDesc(purchaseRequestId);
        List<ComparisonReportResponse> responses = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            responses.add(ComparisonReportResponse.from(reports.get(i), fromJson(reports.get(i).getDetails()), i == 0));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public ComparisonReportResponse get(Long reportId) {
        ComparisonReport report = find(reportId);
        boolean latest = latestFor(report.getPurchaseRequest().getId())
                .map(r -> r.getId().equals(reportId)).orElse(false);
        return ComparisonReportResponse.from(report, fromJson(report.getDetails()), latest);
    }

    public ComparisonReport find(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Comparison report", reportId));
    }

    public Optional<ComparisonReport> latestFor(Long purchaseRequestId) {
        return reportRepository.findFirstByPurchaseRequestIdOrderByReportVersionDesc(purchaseRequestId);
    }

    /** True when any compared quotation changed after the report was generated. */
    public boolean isStale(ComparisonReport report) {
        Set<Long> ids = report.getQuotations().stream().map(cq -> cq.getQuotation().getId()).collect(Collectors.toSet());
        Map<Long, Long> current = quotationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Quotation::getId, Quotation::getVersion));
        return report.getQuotations().stream()
                .anyMatch(cq -> !cq.getQuotationVersion().equals(current.get(cq.getQuotation().getId())));
    }

    public ComparisonResult fromJson(String json) {
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
