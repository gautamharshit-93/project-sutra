package com.sutra.quotation.service.impl;

import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.enums.ApprovalDecision;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.quotation.VendorPortalResponses.Outcome;
import com.sutra.api.quotation.VendorPortalResponses.RfqDetail;
import com.sutra.api.quotation.VendorPortalResponses.RfqSummary;
import com.sutra.api.quotation.VendorPortalResponses.Stage;
import com.sutra.api.quotation.VendorPortalResponses.VendorQuotation;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.common.web.PageResponse;
import com.sutra.quotation.client.DecisionClient;
import com.sutra.quotation.client.DocumentStoreClient;
import com.sutra.quotation.client.MasterDataClient;
import com.sutra.quotation.client.RfqClient;
import com.sutra.quotation.dto.response.DownloadedFile;
import com.sutra.quotation.entity.NamedRef;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationFile;
import com.sutra.quotation.exception.QuotationNotFoundException;
import com.sutra.quotation.mapper.QuotationMapper;
import com.sutra.quotation.mapper.VendorPortalMapper;
import com.sutra.quotation.repository.QuotationFileRepository;
import com.sutra.quotation.repository.QuotationRepository;
import com.sutra.quotation.service.QuotationService;
import com.sutra.quotation.service.VendorPortalService;
import com.sutra.quotation.service.internal.AfterCommit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.sutra.common.audit.AuditService.details;

/**
 * The vendor portal: a supplier's login sees the RFQs open for quotations, submits its own quotation (one per RFQ),
 * follows it and may withdraw it while the RFQ is still open. Every query is limited to the caller's own vendor
 * (taken from the sign-in token), so a vendor never sees another vendor's quotation, the comparison or the review
 * notes.
 */
@Service
@Transactional
public class VendorPortalServiceImpl implements VendorPortalService {

    private final QuotationRepository quotationRepository;
    private final QuotationFileRepository fileRepository;
    private final QuotationService quotationService;
    private final RfqClient rfqs;
    private final DecisionClient decisions;
    private final DocumentStoreClient documents;
    private final MasterDataClient masterData;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final Clock clock;

    public VendorPortalServiceImpl(QuotationRepository quotationRepository, QuotationFileRepository fileRepository,
                                   QuotationService quotationService, RfqClient rfqs, DecisionClient decisions,
                                   DocumentStoreClient documents, MasterDataClient masterData,
                                   CurrentUserProvider currentUser, AuditService auditService, Clock clock) {
        this.quotationRepository = quotationRepository;
        this.fileRepository = fileRepository;
        this.quotationService = quotationService;
        this.rfqs = rfqs;
        this.decisions = decisions;
        this.documents = documents;
        this.masterData = masterData;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RfqSummary> rfqs(int page, int size) {
        Long vendorId = vendorId();
        Map<Long, Quotation> mine = quotationRepository.findByVendorIdOrderByIdDesc(vendorId).stream()
                .collect(Collectors.toMap(Quotation::getPurchaseRequestId, q -> q, (a, b) -> a));
        PageResponse<PurchaseRequestResponse> visible = rfqs.vendorView(mine.keySet(), page, size);
        Map<Long, ApprovalResponse> decided = decisionsFor(visible.content().stream()
                .filter(r -> r.status() == PurchaseRequestStatus.APPROVED).map(PurchaseRequestResponse::id)
                .collect(Collectors.toSet()));
        LocalDate today = LocalDate.now(clock);
        List<RfqSummary> rows = visible.content().stream().map(pr -> {
            Quotation q = mine.get(pr.id());
            return new RfqSummary(pr.id(), pr.requestNumber(), pr.title(), pr.entityName(), pr.currency(),
                    pr.requiredBy(), pr.items().size(), VendorPortalMapper.acceptsVendorQuotations(pr, today),
                    pr.createdAt(), q == null ? null : q.getId(), q == null ? null : Stage.of(q.getStatus()),
                    q == null ? null : outcome(q, pr, decided.get(pr.id())), pr.quotationsDue());
        }).toList();
        return new PageResponse<>(rows, visible.page(), visible.size(), visible.totalElements(), visible.totalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public RfqDetail rfq(Long rfqId) {
        Long vendorId = vendorId();
        List<Quotation> mine = quotationRepository.findByPurchaseRequestIdAndVendorId(rfqId, vendorId);
        PurchaseRequestResponse pr = visibleRfq(rfqId, !mine.isEmpty());
        Map<Long, ApprovalResponse> decided = decisionsFor(pr.status() == PurchaseRequestStatus.APPROVED ? Set.of(rfqId) : Set.of());
        return VendorPortalMapper.rfqDetail(pr, mine.stream().map(q -> toVendorQuotation(q, pr, decided.get(rfqId))).toList(),
                LocalDate.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VendorQuotation> myQuotations() {
        List<Quotation> mine = quotationRepository.findByVendorIdOrderByIdDesc(vendorId());
        Map<Long, PurchaseRequestResponse> byId = rfqs.getMany(mine.stream().map(Quotation::getPurchaseRequestId)
                .collect(Collectors.toSet()));
        Map<Long, ApprovalResponse> decided = decisionsFor(byId.values().stream()
                .filter(r -> r.status() == PurchaseRequestStatus.APPROVED).map(PurchaseRequestResponse::id)
                .collect(Collectors.toSet()));
        return mine.stream().filter(q -> byId.containsKey(q.getPurchaseRequestId()))
                .map(q -> toVendorQuotation(q, byId.get(q.getPurchaseRequestId()), decided.get(q.getPurchaseRequestId())))
                .toList();
    }

    @Override
    public VendorQuotation submit(Long rfqId, List<MultipartFile> files) {
        Long vendorId = vendorId();
        PurchaseRequestResponse pr = rfqs.get(rfqId);
        if (pr.status() != PurchaseRequestStatus.OPEN) {
            throw new BusinessRuleException("RFQ_CLOSED", pr.requestNumber() + " is no longer accepting quotations");
        }
        if (!VendorPortalMapper.acceptsVendorQuotations(pr, LocalDate.now(clock))) {
            throw new BusinessRuleException("RFQ_DUE_DATE_PASSED", "Quotations for " + pr.requestNumber()
                    + " were due by " + pr.quotationsDue() + "; the buyer no longer accepts new ones");
        }
        if (!quotationRepository.findByPurchaseRequestIdAndVendorId(rfqId, vendorId).isEmpty()) {
            throw new DuplicateResourceException("You have already submitted a quotation for " + pr.requestNumber()
                    + ". Withdraw it first to send a new one.");
        }
        MasterDataClient.VendorSnapshot vendor = masterData.activeVendor(vendorId);
        Quotation q = quotationService.submitForVendor(pr, new NamedRef(vendor.id(), vendor.name()), files);
        return toVendorQuotation(q, pr, null);
    }

    @Override
    public void withdraw(Long quotationId) {
        Quotation q = ownQuotation(quotationId);
        PurchaseRequestResponse pr = rfqs.get(q.getPurchaseRequestId());
        if (!canWithdraw(q, pr)) {
            throw new BusinessRuleException("CANNOT_WITHDRAW",
                    "This quotation can no longer be withdrawn: the RFQ is closed or the quotation is being compared");
        }
        List<Long> documentIds = fileRepository.findByQuotationIdOrderByIdAsc(quotationId).stream()
                .map(QuotationFile::getDocumentId).toList();
        // Recorded first: the audit event is grouped under the RFQ, which is looked up from the quotation
        auditService.record(QuotationServiceImpl.AUDIT_TYPE, quotationId, "WITHDRAWN_BY_VENDOR",
                details("purchaseRequest", q.getRequestNumber(), "vendor", q.getVendor().getName()));
        quotationRepository.delete(q);
        AfterCommit.run(() -> documents.delete(documentIds));
    }

    @Override
    public DownloadedFile download(Long quotationId, Long fileId) {
        Quotation q = ownQuotation(quotationId);
        QuotationFile file = fileRepository.findByIdAndQuotationId(fileId, q.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Quotation file", fileId));
        return new DownloadedFile(file.getFileName(), file.getContentType(), file.getSizeBytes(),
                documents.content(file.getDocumentId()));
    }

    private Long vendorId() {
        SutraUserPrincipal principal = currentUser.requirePrincipal();
        if (principal.getVendorId() == null) {
            throw new ForbiddenOperationException("This login is not linked to a vendor");
        }
        return principal.getVendorId();
    }

    /** Closed RFQs the vendor did not quote on stay hidden (reported as not found). */
    private PurchaseRequestResponse visibleRfq(Long rfqId, boolean quoted) {
        PurchaseRequestResponse pr = rfqs.get(rfqId);
        if (pr.status() != PurchaseRequestStatus.OPEN && !quoted) {
            throw new ResourceNotFoundException("PURCHASE_REQUEST_NOT_FOUND",
                    "Purchase request with id " + rfqId + " was not found") { };
        }
        return pr;
    }

    /** The quotation, which must belong to the caller's vendor (otherwise it is reported as not found). */
    private Quotation ownQuotation(Long quotationId) {
        Quotation q = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));
        if (!q.getVendor().getId().equals(vendorId())) {
            throw new QuotationNotFoundException(quotationId);
        }
        return q;
    }

    private boolean canWithdraw(Quotation q, PurchaseRequestResponse pr) {
        return pr.status() == PurchaseRequestStatus.OPEN
                && q.getStatus() != QuotationStatus.PROCESSING
                && decisions.comparisonReferences(q.getId()) == 0;
    }

    private Map<Long, ApprovalResponse> decisionsFor(Set<Long> approvedRfqIds) {
        return approvedRfqIds.isEmpty() ? Map.of() : decisions.latestDecisions(approvedRfqIds);
    }

    private static Outcome outcome(Quotation q, PurchaseRequestResponse pr, ApprovalResponse latest) {
        PurchaseRequestStatus status = pr.status();
        if (status == PurchaseRequestStatus.CANCELLED || status == PurchaseRequestStatus.REJECTED) {
            return Outcome.CLOSED;
        }
        if (status != PurchaseRequestStatus.APPROVED) {
            return Outcome.PENDING;
        }
        boolean won = latest != null && latest.decision() == ApprovalDecision.APPROVED
                && q.getId().equals(latest.selectedQuotationId());
        return won ? Outcome.AWARDED : Outcome.NOT_AWARDED;
    }

    private VendorQuotation toVendorQuotation(Quotation q, PurchaseRequestResponse pr, ApprovalResponse latest) {
        // The vendor sees the backend's total only once the reading is accepted for comparison
        boolean confirmed = q.getStatus() == QuotationStatus.READY_FOR_COMPARISON;
        boolean withdrawable = pr.status() == PurchaseRequestStatus.OPEN && q.getStatus() != QuotationStatus.PROCESSING
                && (q.getId() == null || decisions.comparisonReferences(q.getId()) == 0);
        return new VendorQuotation(q.getId(), pr.id(), pr.requestNumber(), pr.title(), q.getQuotationNumber(),
                Stage.of(q.getStatus()), confirmed ? q.getGrandTotal() : null, q.getCurrency(), q.getValidUntil(),
                q.isSubmittedByVendor(), withdrawable, outcome(q, pr, latest),
                fileRepository.findByQuotationIdOrderByIdAsc(q.getId()).stream().map(QuotationMapper::fileResponse).toList(),
                q.getCreatedAt());
    }
}
