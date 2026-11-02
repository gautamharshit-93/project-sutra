package com.sutra.procurement.service;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.common.web.PageResponse;
import com.sutra.procurement.client.MasterDataClient;
import com.sutra.procurement.entity.Approval;
import com.sutra.procurement.entity.NamedRef;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationFile;
import com.sutra.procurement.entity.enums.ApprovalDecision;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.exception.PurchaseRequestNotFoundException;
import com.sutra.procurement.exception.QuotationNotFoundException;
import com.sutra.procurement.repository.ApprovalRepository;
import com.sutra.procurement.repository.PurchaseRequestRepository;
import com.sutra.procurement.repository.QuotationFileRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.response.QuotationResponses.FileResponse;
import com.sutra.procurement.response.VendorPortalResponses.Outcome;
import com.sutra.procurement.response.VendorPortalResponses.RfqDetail;
import com.sutra.procurement.response.VendorPortalResponses.RfqSummary;
import com.sutra.procurement.response.VendorPortalResponses.Stage;
import com.sutra.procurement.response.VendorPortalResponses.VendorQuotation;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The vendor portal: a supplier's login sees the RFQs open for quotations, submits its own quotation (one per RFQ),
 * follows it and may withdraw it while the RFQ is still open. Every query is limited to the caller's own vendor, so
 * a vendor can never see another vendor's quotation, the comparison or internal review notes.
 */
@Service
@Transactional
public class VendorPortalService {

    private final PurchaseRequestRepository purchaseRequestRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationFileRepository fileRepository;
    private final ApprovalRepository approvalRepository;
    private final QuotationService quotationService;
    private final MasterDataClient masterData;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;

    public VendorPortalService(PurchaseRequestRepository purchaseRequestRepository,
                               QuotationRepository quotationRepository, QuotationFileRepository fileRepository,
                               ApprovalRepository approvalRepository, QuotationService quotationService,
                               MasterDataClient masterData, CurrentUserProvider currentUser,
                               AuditService auditService) {
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.quotationRepository = quotationRepository;
        this.fileRepository = fileRepository;
        this.approvalRepository = approvalRepository;
        this.quotationService = quotationService;
        this.masterData = masterData;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<RfqSummary> rfqs(int page, int size) {
        Long vendorId = vendorId();
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.of(purchaseRequestRepository.visibleToVendor(vendorId, pageable), pr -> {
            Quotation mine = quotationRepository.findByPurchaseRequestIdAndVendorId(pr.getId(), vendorId).stream()
                    .findFirst().orElse(null);
            return new RfqSummary(pr.getId(), pr.getRequestNumber(), pr.getTitle(), pr.getEntity().getName(),
                    pr.getCurrency(), pr.getRequiredBy(), pr.getItems().size(),
                    pr.getStatus() == PurchaseRequestStatus.OPEN, pr.getCreatedAt(),
                    mine == null ? null : mine.getId(), mine == null ? null : Stage.of(mine.getStatus()),
                    mine == null ? null : outcome(mine));
        });
    }

    @Transactional(readOnly = true)
    public RfqDetail rfq(Long rfqId) {
        Long vendorId = vendorId();
        PurchaseRequest pr = visibleRfq(rfqId, vendorId);
        return RfqDetail.of(pr, quotationRepository.findByPurchaseRequestIdAndVendorId(rfqId, vendorId).stream()
                .map(this::toVendorQuotation).toList());
    }

    @Transactional(readOnly = true)
    public List<VendorQuotation> myQuotations() {
        return quotationRepository.findByVendorIdOrderByIdDesc(vendorId()).stream().map(this::toVendorQuotation).toList();
    }

    /** Submits the vendor's quotation (one or more files) for an open RFQ. Extraction starts in the background. */
    public VendorQuotation submit(Long rfqId, List<MultipartFile> files) {
        Long vendorId = vendorId();
        PurchaseRequest pr = purchaseRequestRepository.findById(rfqId)
                .orElseThrow(() -> new PurchaseRequestNotFoundException(rfqId));
        if (pr.getStatus() != PurchaseRequestStatus.OPEN) {
            throw new BusinessRuleException("RFQ_CLOSED", pr.getRequestNumber() + " is no longer accepting quotations");
        }
        if (!quotationRepository.findByPurchaseRequestIdAndVendorId(rfqId, vendorId).isEmpty()) {
            throw new DuplicateResourceException("You have already submitted a quotation for " + pr.getRequestNumber()
                    + ". Withdraw it first to send a new one.");
        }
        MasterDataClient.VendorSnapshot vendor = masterData.activeVendor(vendorId);
        Quotation q = quotationService.submitForVendor(pr, new NamedRef(vendor.id(), vendor.name()), files);
        return toVendorQuotation(q);
    }

    /** Withdraws the vendor's own quotation while the RFQ is open and before it was compared. */
    public void withdraw(Long quotationId) {
        Quotation q = ownQuotation(quotationId);
        if (!canWithdraw(q)) {
            throw new BusinessRuleException("CANNOT_WITHDRAW",
                    "This quotation can no longer be withdrawn: the RFQ is closed or the quotation is being compared");
        }
        // Recorded first: the audit event is grouped under the RFQ, which is looked up from the quotation
        auditService.record(QuotationService.AUDIT_TYPE, quotationId, "WITHDRAWN_BY_VENDOR",
                details("purchaseRequest", q.getPurchaseRequest().getRequestNumber(), "vendor", q.getVendor().getName()));
        quotationRepository.delete(q);
    }

    /** One of the vendor's own original files. */
    public QuotationFile download(Long quotationId, Long fileId) {
        Quotation q = ownQuotation(quotationId);
        return fileRepository.findByIdAndQuotationId(fileId, q.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Quotation file", fileId));
    }

    private Long vendorId() {
        SutraUserPrincipal principal = currentUser.requirePrincipal();
        if (principal.getVendorId() == null) {
            throw new ForbiddenOperationException("This login is not linked to a vendor");
        }
        return principal.getVendorId();
    }

    private PurchaseRequest visibleRfq(Long rfqId, Long vendorId) {
        PurchaseRequest pr = purchaseRequestRepository.findById(rfqId)
                .orElseThrow(() -> new PurchaseRequestNotFoundException(rfqId));
        boolean quoted = !quotationRepository.findByPurchaseRequestIdAndVendorId(rfqId, vendorId).isEmpty();
        if (pr.getStatus() != PurchaseRequestStatus.OPEN && !quoted) {
            throw new PurchaseRequestNotFoundException(rfqId); // closed RFQs the vendor did not quote on stay hidden
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

    private boolean canWithdraw(Quotation q) {
        return q.getPurchaseRequest().getStatus() == PurchaseRequestStatus.OPEN
                && q.getStatus() != QuotationStatus.PROCESSING
                && quotationRepository.countComparisonReferences(q.getId()) == 0;
    }

    private Outcome outcome(Quotation q) {
        PurchaseRequestStatus status = q.getPurchaseRequest().getStatus();
        if (status == PurchaseRequestStatus.CANCELLED || status == PurchaseRequestStatus.REJECTED) {
            return Outcome.CLOSED;
        }
        if (status != PurchaseRequestStatus.APPROVED) {
            return Outcome.PENDING;
        }
        Approval latest = approvalRepository.findByPurchaseRequestIdOrderByDecidedAtDesc(q.getPurchaseRequest().getId())
                .stream().findFirst().orElse(null);
        boolean won = latest != null && latest.getDecision() == ApprovalDecision.APPROVED
                && latest.getSelectedQuotation() != null && latest.getSelectedQuotation().getId().equals(q.getId());
        return won ? Outcome.AWARDED : Outcome.NOT_AWARDED;
    }

    private VendorQuotation toVendorQuotation(Quotation q) {
        PurchaseRequest pr = q.getPurchaseRequest();
        // The vendor sees the backend's total only once a buyer has confirmed the reading
        boolean confirmed = q.getStatus() == QuotationStatus.READY_FOR_COMPARISON;
        return new VendorQuotation(q.getId(), pr.getId(), pr.getRequestNumber(), pr.getTitle(), q.getQuotationNumber(),
                Stage.of(q.getStatus()), confirmed ? q.getGrandTotal() : null, q.getCurrency(), q.getValidUntil(),
                q.isSubmittedByVendor(), canWithdraw(q), outcome(q),
                fileRepository.findMetadataByQuotationId(q.getId()).stream().map(FileResponse::from).toList(),
                q.getCreatedAt());
    }
}
