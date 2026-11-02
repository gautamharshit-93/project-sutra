package com.sutra.procurement.service;

import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.procurement.ai.ExtractionChoice;
import com.sutra.procurement.ai.ExtractionEngine;
import com.sutra.procurement.ai.ExtractionEngines;
import com.sutra.procurement.calculation.FlagCodes;
import com.sutra.procurement.client.MasterDataClient;
import com.sutra.procurement.entity.NamedRef;
import com.sutra.procurement.entity.UserRef;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationFile;
import com.sutra.procurement.entity.QuotationFlag;
import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.RequestItem;
import com.sutra.procurement.entity.enums.ExtractionSource;
import com.sutra.procurement.entity.enums.FileFormat;
import com.sutra.procurement.entity.enums.FlagSeverity;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.procurement.exception.InvalidQuotationFileException;
import com.sutra.procurement.exception.QuotationNotFoundException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.procurement.repository.QuotationFileRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.request.QuotationRequests.QuotationItemRequest;
import com.sutra.procurement.request.QuotationRequests.QuotationUpdateRequest;
import com.sutra.common.web.PageResponse;
import com.sutra.procurement.response.QuotationResponses.QuotationResponse;
import com.sutra.procurement.response.QuotationResponses.QuotationSummary;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.procurement.security.RecordAccessPolicy;
import com.sutra.procurement.service.QuotationProcessingEvents.ProcessQuotationRequested;
import com.sutra.common.util.Text;
import com.sutra.procurement.validation.QuotationFileValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Quotation upload, review and manual correction.
 *
 * <p>A quotation can consist of several files (e.g. the same offer as PDF, Word and Excel, or a quotation plus a
 * price annexure). All files stay linked to the same quotation and are extracted together.</p>
 */
@Service
@Transactional
public class QuotationService {

    public static final String AUDIT_TYPE = "QUOTATION";

    /** Upper limit of files in one quotation. */
    public static final int MAX_FILES_PER_QUOTATION = 10;

    /** A quotation stuck in PROCESSING longer than this (e.g. after a restart) may be retried. */
    static final Duration STALE_PROCESSING = Duration.ofMinutes(15);

    private static final Set<PurchaseRequestStatus> ACCEPTS_QUOTATIONS =
            Set.of(PurchaseRequestStatus.OPEN, PurchaseRequestStatus.UNDER_REVIEW);
    private static final Set<String> EXTRACTION_ITEM_CODES = Set.of(FlagCodes.LOW_CONFIDENCE);

    private final QuotationRepository quotationRepository;
    private final QuotationFileRepository fileRepository;
    private final PurchaseRequestService purchaseRequestService;
    private final MasterDataClient masterData;
    private final ExtractionEngines engines;
    private final QuotationFileValidator fileValidator;
    private final QuotationReviewService reviewService;
    private final RecordAccessPolicy accessPolicy;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    public QuotationService(QuotationRepository quotationRepository, QuotationFileRepository fileRepository,
                            PurchaseRequestService purchaseRequestService, MasterDataClient masterData, ExtractionEngines engines,
                            QuotationFileValidator fileValidator, QuotationReviewService reviewService,
                            RecordAccessPolicy accessPolicy, CurrentUserProvider currentUser,
                            AuditService auditService, ApplicationEventPublisher events,
                            EntityManager entityManager, Clock clock) {
        this.quotationRepository = quotationRepository;
        this.fileRepository = fileRepository;
        this.purchaseRequestService = purchaseRequestService;
        this.masterData = masterData;
        this.engines = engines;
        this.fileValidator = fileValidator;
        this.reviewService = reviewService;
        this.accessPolicy = accessPolicy;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ---------- queries ----------

    @Transactional(readOnly = true)
    public List<QuotationSummary> listForRequest(Long purchaseRequestId) {
        purchaseRequestService.find(purchaseRequestId);
        return quotationRepository.findByPurchaseRequestIdOrderByIdAsc(purchaseRequestId).stream()
                .map(q -> QuotationSummary.from(q, fileRepository.countByQuotationId(q.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public QuotationResponse get(Long id) {
        return toResponse(find(id));
    }

    /** Returns one original file (with bytes) and records the access. */
    public QuotationFile download(Long quotationId, Long fileId) {
        find(quotationId);
        QuotationFile file = fileRepository.findByIdAndQuotationId(fileId, quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation file", fileId));
        auditService.record(AUDIT_TYPE, quotationId, "FILE_DOWNLOADED", details("fileName", file.getFileName()));
        return file;
    }

    // ---------- upload and processing ----------

    /** Procurement uploads a vendor's quotation (one or more files). */
    public QuotationResponse upload(Long purchaseRequestId, Long vendorId, List<MultipartFile> files) {
        return upload(purchaseRequestId, vendorId, files, ExtractionEngine.AUTO);
    }

    /** As above, read by the chosen extraction engine (AUTO tries the configured engines in order). */
    public QuotationResponse upload(Long purchaseRequestId, Long vendorId, List<MultipartFile> files,
                                    ExtractionEngine engine) {
        return upload(purchaseRequestId, vendorId, files, ExtractionChoice.of(engine));
    }

    /** As above, with the Gemini model to try first. */
    public QuotationResponse upload(Long purchaseRequestId, Long vendorId, List<MultipartFile> files,
                                    ExtractionChoice choice) {
        engines.validate(choice);
        PurchaseRequest pr = purchaseRequestService.findModifiable(purchaseRequestId);
        return toResponse(createQuotation(pr, vendorRef(vendorId), files, UserRef.of(currentUser.requirePrincipal()),
                choice, false));
    }

    /**
     * A vendor submits its own quotation through the vendor portal. The caller (VendorPortalService) has checked
     * that the RFQ is open and that the vendor has no other quotation on it.
     */
    public Quotation submitForVendor(PurchaseRequest pr, NamedRef vendor, List<MultipartFile> files) {
        return createQuotation(pr, vendor, files, UserRef.of(currentUser.requirePrincipal()), ExtractionChoice.AUTO, true);
    }

    /** Creates a quotation with its files and queues extraction. The caller has checked who may upload. */
    private Quotation createQuotation(PurchaseRequest pr, NamedRef vendor, List<MultipartFile> files, UserRef user,
                                      ExtractionChoice choice, boolean submittedByVendor) {
        requireAcceptsQuotations(pr);
        List<ValidatedFile> validated = validateFiles(files, pr.getId(), 0);

        Quotation q = new Quotation();
        q.setPurchaseRequest(pr);
        q.setVendor(vendor);
        q.setStatus(QuotationStatus.UPLOADED);
        q.setUploadedBy(user);
        q.setSubmittedByVendor(submittedByVendor);
        q = quotationRepository.save(q);
        storeFiles(q, validated, user);

        auditService.record(AUDIT_TYPE, q.getId(), "UPLOADED", details("purchaseRequest", pr.getRequestNumber(),
                "vendor", vendor.getName(), "files", fileSummary(validated), "engine", choice.describe(),
                "submittedByVendor", submittedByVendor));
        events.publishEvent(new ProcessQuotationRequested(q.getId(), choice));
        return q;
    }

    /** Procurement adds files to an existing quotation; extraction re-runs over all files. */
    public QuotationResponse addFiles(Long quotationId, List<MultipartFile> files) {
        Quotation q = findEditable(quotationId, false);
        return toResponse(appendFiles(q, files, UserRef.of(currentUser.requirePrincipal())));
    }

    /** Adds files and re-queues extraction. The caller has checked access and editability. */
    private Quotation appendFiles(Quotation q, List<MultipartFile> files, UserRef user) {
        requireAcceptsQuotations(q.getPurchaseRequest());
        long existing = fileRepository.countByQuotationId(q.getId());
        List<ValidatedFile> validated = validateFiles(files, q.getPurchaseRequest().getId(), existing);
        storeFiles(q, validated, user);
        requeue(q);
        auditService.record(AUDIT_TYPE, q.getId(), "FILES_ADDED", details("files", fileSummary(validated)));
        return q;
    }

    /** Procurement removes one file (a quotation keeps at least one); extraction re-runs. */
    public QuotationResponse removeFile(Long quotationId, Long fileId) {
        Quotation q = findEditable(quotationId, false);
        return toResponse(deleteFile(q, fileId));
    }

    private Quotation deleteFile(Quotation q, Long fileId) {
        QuotationFile file = fileRepository.findByIdAndQuotationId(fileId, q.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Quotation file", fileId));
        if (fileRepository.countByQuotationId(q.getId()) <= 1) {
            throw new BusinessRuleException("LAST_FILE", "A quotation must keep at least one file; delete the quotation instead");
        }
        fileRepository.delete(file);
        fileRepository.flush();
        requeue(q);
        auditService.record(AUDIT_TYPE, q.getId(), "FILE_REMOVED", details("fileName", file.getFileName()));
        return q;
    }

    /** Queues extraction again. Previous extracted lines and flags are replaced. */
    public QuotationResponse reprocess(Long id) {
        return reprocess(id, ExtractionEngine.AUTO);
    }

    /** Queues extraction again with the chosen engine, e.g. to switch engines after a cross-check. */
    public QuotationResponse reprocess(Long id, ExtractionEngine engine) {
        return reprocess(id, ExtractionChoice.of(engine));
    }

    /** As above, with the Gemini model to try first. */
    public QuotationResponse reprocess(Long id, ExtractionChoice choice) {
        engines.validate(choice);
        Quotation q = findEditable(id, true);
        requeue(q, choice);
        auditService.record(AUDIT_TYPE, id, "REPROCESS_REQUESTED", details("attempt", q.getProcessingAttempts() + 1,
                "engine", choice.describe()));
        return toResponse(q);
    }

    private void requeue(Quotation q) {
        requeue(q, ExtractionChoice.AUTO);
    }

    private void requeue(Quotation q, ExtractionChoice choice) {
        q.setStatus(QuotationStatus.UPLOADED);
        q.setFailureReason(null);
        events.publishEvent(new ProcessQuotationRequested(q.getId(), choice));
    }

    /** An active vendor from master data, as kept on the quotation. */
    private NamedRef vendorRef(Long vendorId) {
        MasterDataClient.VendorSnapshot vendor = masterData.activeVendor(vendorId);
        return new NamedRef(vendor.id(), vendor.name());
    }


    // ---------- manual review and correction ----------

    public QuotationResponse updateHeader(Long id, QuotationUpdateRequest request) {
        Quotation q = findEditable(id, false);
        if (!q.getVendor().getId().equals(request.vendorId())) {
            q.setVendor(vendorRef(request.vendorId()));
        }
        q.setQuotationNumber(Text.blankToNull(request.quotationNumber()));
        q.setQuotationDate(request.quotationDate());
        q.setValidUntil(request.validUntil());
        q.setCurrency(Text.blankToNull(request.currency()));
        q.setStatedSubtotal(request.statedSubtotal());
        q.setStatedTaxTotal(request.statedTaxTotal());
        q.setStatedGrandTotal(request.statedGrandTotal());
        q.setDeliveryTerms(Text.blankToNull(request.deliveryTerms()));
        q.setPaymentTerms(Text.blankToNull(request.paymentTerms()));
        q.setWarrantyTerms(Text.blankToNull(request.warrantyTerms()));
        q.setNotes(Text.blankToNull(request.notes()));
        markManual(q);
        reviewService.revalidate(q);
        auditService.record(AUDIT_TYPE, id, "HEADER_CORRECTED", details("vendorId", request.vendorId(),
                "quotationNumber", q.getQuotationNumber(), "validUntil", q.getValidUntil(), "currency", q.getCurrency(),
                "statedGrandTotal", q.getStatedGrandTotal()));
        return toResponse(q);
    }

    public QuotationResponse addItem(Long id, QuotationItemRequest request) {
        Quotation q = findEditable(id, false);
        QuotationItem item = new QuotationItem();
        item.setLineNumber(q.nextLineNumber());
        applyItem(q, item, request);
        q.addItem(item);
        quotationRepository.flush();
        q.getFlags().removeIf(f -> !f.isResolved() && FlagCodes.NO_ITEMS_EXTRACTED.equals(f.getCode()));
        markManual(q);
        reviewService.revalidate(q);
        auditService.record(AUDIT_TYPE, id, "ITEM_ADDED", itemDetails(item));
        return toResponse(q);
    }

    public QuotationResponse updateItem(Long id, Long itemId, QuotationItemRequest request) {
        Quotation q = findEditable(id, false);
        QuotationItem item = findItem(q, itemId);
        var before = itemDetails(item);
        applyItem(q, item, request);
        // A person has checked this line, so its extraction-confidence flags are settled.
        UserRef user = UserRef.of(currentUser.requirePrincipal());
        q.getFlags().stream()
                .filter(f -> !f.isResolved() && f.getQuotationItem() == item && EXTRACTION_ITEM_CODES.contains(f.getCode()))
                .forEach(f -> f.resolve(user));
        markManual(q);
        reviewService.revalidate(q);
        auditService.record(AUDIT_TYPE, id, "ITEM_CORRECTED", details("before", before, "after", itemDetails(item)));
        return toResponse(q);
    }

    public QuotationResponse deleteItem(Long id, Long itemId) {
        Quotation q = findEditable(id, false);
        QuotationItem item = findItem(q, itemId);
        q.getFlags().removeIf(f -> f.getQuotationItem() == item);
        q.getItems().remove(item);
        quotationRepository.flush();
        markManual(q);
        reviewService.revalidate(q);
        auditService.record(AUDIT_TYPE, id, "ITEM_DELETED", itemDetails(item));
        return toResponse(q);
    }

    public QuotationResponse resolveFlag(Long id, Long flagId, String comment) {
        Quotation q = findEditable(id, false);
        QuotationFlag flag = q.getFlags().stream().filter(f -> f.getId().equals(flagId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Review flag", flagId));
        if (flag.isResolved()) {
            return toResponse(q);
        }
        if (flag.getSeverity() == FlagSeverity.ERROR) {
            throw new BusinessRuleException("ERROR_FLAG_NEEDS_FIX",
                    "Errors cannot be dismissed; correct the data instead (" + flag.getMessage() + ")");
        }
        flag.resolve(UserRef.of(currentUser.requirePrincipal()));
        reviewService.updateStatus(q);
        auditService.record(AUDIT_TYPE, id, "FLAG_RESOLVED", details("flag", flag.getCode(), "field",
                flag.getFieldName(), "message", flag.getMessage(), "comment", Text.blankToNull(comment)));
        return toResponse(q);
    }

    /**
     * The reviewer confirms the quotation is ready. All errors must be fixed first; remaining warnings are
     * recorded as acknowledged by this user.
     */
    public QuotationResponse markReady(Long id) {
        Quotation q = findEditable(id, false);
        reviewService.revalidate(q);
        if (q.getItems().isEmpty()) {
            throw new BusinessRuleException("NO_ITEMS", "Add at least one line item before marking the quotation ready");
        }
        if (QuotationReviewService.hasOpenErrors(q)) {
            throw new BusinessRuleException("OPEN_ERRORS", "Fix all error flags before marking the quotation ready");
        }
        UserRef user = UserRef.of(currentUser.requirePrincipal());
        List<QuotationFlag> acknowledged = q.getFlags().stream()
                .filter(f -> !f.isResolved() && f.getSeverity() == FlagSeverity.WARNING).toList();
        acknowledged.forEach(f -> f.resolve(user));
        q.setStatus(QuotationStatus.READY_FOR_COMPARISON);
        auditService.record(AUDIT_TYPE, id, "MARKED_READY", details("acknowledgedWarnings",
                acknowledged.stream().map(QuotationFlag::getMessage).toList(), "grandTotal", q.getGrandTotal()));
        return toResponse(q);
    }

    public void delete(Long id) {
        Quotation q = find(id);
        accessPolicy.requireModify(q.getPurchaseRequest());
        if (!q.getPurchaseRequest().isEditable()) {
            throw new BusinessRuleException("PR_CLOSED", "Quotations of a closed request cannot be deleted");
        }
        if (q.getStatus() == QuotationStatus.PROCESSING) {
            throw new BusinessRuleException("QUOTATION_PROCESSING", "Wait for processing to finish before deleting");
        }
        if (quotationRepository.countComparisonReferences(id) > 0) {
            throw new BusinessRuleException("QUOTATION_IN_COMPARISON",
                    "This quotation is part of a saved comparison report and is kept for the audit trail");
        }
        fileRepository.deleteAll(fileRepository.findByQuotationIdOrderByIdAsc(id));
        quotationRepository.delete(q);
        auditService.record(AUDIT_TYPE, id, "DELETED", details("purchaseRequest", q.getPurchaseRequest().getRequestNumber(),
                "vendor", q.getVendor().getName()));
        // Also on the request, so its timeline still shows the quotation after the row is gone.
        auditService.record(PurchaseRequestService.AUDIT_TYPE, q.getPurchaseRequest().getId(), "QUOTATION_DELETED",
                details("quotationId", id, "vendor", q.getVendor().getName()));
    }

    // ---------- helpers ----------

    public Quotation find(Long id) {
        return quotationRepository.findById(id).orElseThrow(() -> new QuotationNotFoundException(id));
    }

    public QuotationResponse toResponse(Quotation q) {
        return QuotationResponse.from(q, fileRepository.findMetadataByQuotationId(q.getId()));
    }

    private Quotation findEditable(Long id, boolean allowStaleProcessing) {
        Quotation q = find(id);
        accessPolicy.requireModify(q.getPurchaseRequest());
        if (!q.getPurchaseRequest().isEditable()) {
            throw new BusinessRuleException("PR_CLOSED", q.getPurchaseRequest().getRequestNumber() + " is "
                    + q.getPurchaseRequest().getStatus() + "; its quotations can no longer change");
        }
        requireNotProcessing(q, allowStaleProcessing);
        // Any edit to items or flags bumps the quotation version, so saved comparisons can detect stale data.
        entityManager.lock(q, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        return q;
    }

    private void requireNotProcessing(Quotation q, boolean allowStale) {
        if (q.getStatus() == QuotationStatus.PROCESSING) {
            boolean stale = q.getUpdatedAt() != null
                    && q.getUpdatedAt().isBefore(Instant.now(clock).minus(STALE_PROCESSING));
            if (!(allowStale && stale)) {
                throw new BusinessRuleException("QUOTATION_PROCESSING",
                        "The quotation is being processed; wait until extraction finishes");
            }
        }
    }

    private static void requireAcceptsQuotations(PurchaseRequest pr) {
        if (!ACCEPTS_QUOTATIONS.contains(pr.getStatus())) {
            throw new BusinessRuleException("PR_NOT_OPEN",
                    pr.getRequestNumber() + " is not accepting quotations (it is " + pr.getStatus() + ")");
        }
        if (pr.getItems().isEmpty()) {
            throw new BusinessRuleException("PR_HAS_NO_ITEMS", "Add the requested items before uploading quotations");
        }
    }

    private List<ValidatedFile> validateFiles(List<MultipartFile> files, Long purchaseRequestId, long existingCount) {
        List<MultipartFile> nonEmpty = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            throw new InvalidQuotationFileException("No file was uploaded, or the files are empty.");
        }
        if (existingCount + nonEmpty.size() > MAX_FILES_PER_QUOTATION) {
            throw new InvalidQuotationFileException("A quotation can have at most " + MAX_FILES_PER_QUOTATION
                    + " files (it has " + existingCount + ", " + nonEmpty.size() + " more were uploaded).");
        }
        List<ValidatedFile> validated = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        for (MultipartFile multipartFile : nonEmpty) {
            String fileName = QuotationFileValidator.sanitiseFileName(multipartFile.getOriginalFilename());
            byte[] bytes = readBytes(multipartFile);
            FileFormat format = fileValidator.validate(fileName, bytes);
            String sha256 = sha256(bytes);
            if (!hashes.add(sha256)) {
                throw new DuplicateResourceException("'" + fileName + "' was selected twice");
            }
            if (quotationRepository.existsFileInRequest(sha256, purchaseRequestId)) {
                throw new DuplicateResourceException("'" + fileName + "' was already uploaded for this purchase request");
            }
            validated.add(new ValidatedFile(fileName, format, bytes, sha256));
        }
        return validated;
    }

    private void storeFiles(Quotation q, List<ValidatedFile> files, UserRef user) {
        for (ValidatedFile v : files) {
            QuotationFile file = new QuotationFile();
            file.setQuotation(q);
            file.setFileName(v.fileName());
            file.setContentType(v.format().mimeType());
            file.setFileFormat(v.format());
            file.setSizeBytes(v.bytes().length);
            file.setSha256(v.sha256());
            file.setContent(v.bytes());
            file.setUploadedBy(user);
            file.setUploadedAt(Instant.now(clock));
            fileRepository.save(file);
        }
    }

    private static List<Map<String, Object>> fileSummary(List<ValidatedFile> files) {
        return files.stream()
                .map(f -> details("fileName", f.fileName(), "format", f.format(), "sizeBytes", f.bytes().length))
                .toList();
    }

    private static QuotationItem findItem(Quotation q, Long itemId) {
        return q.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Quotation item", itemId));
    }

    private void applyItem(Quotation q, QuotationItem item, QuotationItemRequest request) {
        RequestItem requestItem = null;
        if (request.requestItemId() != null) {
            requestItem = q.getPurchaseRequest().getItems().stream()
                    .filter(r -> r.getId().equals(request.requestItemId())).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("REQUEST_ITEM_MISMATCH",
                            "Request item " + request.requestItemId() + " does not belong to this purchase request"));
        }
        if (!Objects.equals(item.getRequestItem() == null ? null : item.getRequestItem().getId(), request.requestItemId())) {
            // A person chose the match, so it is certain.
            item.setMatchConfidence(requestItem == null ? null : BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP));
        }
        item.setRequestItem(requestItem);
        item.setItemCode(Text.blankToNull(request.itemCode()));
        item.setDescription(request.description().trim());
        item.setQuantity(request.quantity());
        item.setUnitOfMeasure(Text.blankToNull(request.unitOfMeasure()));
        item.setUnitPrice(request.unitPrice());
        item.setDiscountPercent(request.discountPercent());
        item.setDiscountAmount(request.discountAmount());
        item.setTaxPercent(request.taxPercent());
        item.setTaxAmount(request.taxAmount());
        item.setStatedLineTotal(request.statedLineTotal());
        item.setNotes(Text.blankToNull(request.notes()));
        item.setManuallyEdited(true);
    }

    /** A failed or unprocessed quotation that a person fills in becomes a manual entry. */
    private static void markManual(Quotation q) {
        if (q.getExtractionSource() == null) {
            q.setExtractionSource(ExtractionSource.MANUAL);
        }
        if (q.getStatus() == QuotationStatus.FAILED || q.getStatus() == QuotationStatus.UPLOADED) {
            q.setStatus(QuotationStatus.NEEDS_REVIEW);
            q.setFailureReason(null);
        }
    }

    private static Map<String, Object> itemDetails(QuotationItem i) {
        return details("line", i.getLineNumber(), "itemCode", i.getItemCode(), "description", i.getDescription(),
                "quantity", i.getQuantity(), "unitPrice", i.getUnitPrice(), "discountPercent", i.getDiscountPercent(),
                "taxPercent", i.getTaxPercent(), "lineTotal", i.getLineTotal(),
                "requestLine", i.getRequestItem() == null ? null : i.getRequestItem().getLineNumber());
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidQuotationFileException("The uploaded file could not be read.");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ValidatedFile(String fileName, FileFormat format, byte[] bytes, String sha256) {
    }
}
