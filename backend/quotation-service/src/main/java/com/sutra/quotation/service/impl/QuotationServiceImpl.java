package com.sutra.quotation.service.impl;

import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FlagSeverity;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.internal.InternalContracts.RfqQuotationUsage;
import com.sutra.api.quotation.QuotationRequests.QuotationItemRequest;
import com.sutra.api.quotation.QuotationRequests.QuotationUpdateRequest;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationSummary;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import com.sutra.api.rules.FlagCodes;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.util.Text;
import com.sutra.quotation.client.DecisionClient;
import com.sutra.quotation.client.DocumentStoreClient;
import com.sutra.quotation.client.MasterDataClient;
import com.sutra.quotation.client.RfqClient;
import com.sutra.quotation.dto.response.DownloadedFile;
import com.sutra.quotation.entity.NamedRef;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationFile;
import com.sutra.quotation.entity.QuotationFlag;
import com.sutra.quotation.entity.QuotationItem;
import com.sutra.quotation.entity.UserRef;
import com.sutra.quotation.exception.InvalidQuotationFileException;
import com.sutra.quotation.exception.QuotationNotFoundException;
import com.sutra.quotation.mapper.QuotationMapper;
import com.sutra.quotation.repository.QuotationFileRepository;
import com.sutra.quotation.repository.QuotationRepository;
import com.sutra.quotation.service.QuotationService;
import com.sutra.quotation.service.internal.AfterCommit;
import com.sutra.quotation.service.internal.QuotationReviewService;
import com.sutra.quotation.service.internal.ReadingQueue;
import com.sutra.quotation.service.internal.RecordAccessPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

import static com.sutra.common.audit.AuditService.details;

@Service
@Transactional
public class QuotationServiceImpl implements QuotationService {

    public static final String AUDIT_TYPE = "QUOTATION";
    static final String RFQ_AUDIT_TYPE = "PURCHASE_REQUEST";

    /** Upper limit of files in one quotation. */
    public static final int MAX_FILES_PER_QUOTATION = 10;

    /** A quotation stuck in PROCESSING longer than this (e.g. after a restart) may be read again. */
    static final Duration STALE_PROCESSING = Duration.ofMinutes(15);

    private static final Set<PurchaseRequestStatus> ACCEPTS_QUOTATIONS =
            Set.of(PurchaseRequestStatus.OPEN, PurchaseRequestStatus.UNDER_REVIEW);
    private static final Set<String> EXTRACTION_ITEM_CODES = Set.of(FlagCodes.LOW_CONFIDENCE);

    private final QuotationRepository quotationRepository;
    private final QuotationFileRepository fileRepository;
    private final RfqClient rfqs;
    private final MasterDataClient masterData;
    private final DocumentStoreClient documents;
    private final DecisionClient decisions;
    private final ReadingQueue readingQueue;
    private final QuotationReviewService reviewService;
    private final RecordAccessPolicy accessPolicy;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final Clock clock;

    public QuotationServiceImpl(QuotationRepository quotationRepository, QuotationFileRepository fileRepository,
                                RfqClient rfqs, MasterDataClient masterData, DocumentStoreClient documents,
                                DecisionClient decisions, ReadingQueue readingQueue,
                                QuotationReviewService reviewService, RecordAccessPolicy accessPolicy,
                                CurrentUserProvider currentUser, AuditService auditService,
                                EntityManager entityManager, Clock clock) {
        this.quotationRepository = quotationRepository;
        this.fileRepository = fileRepository;
        this.rfqs = rfqs;
        this.masterData = masterData;
        this.documents = documents;
        this.decisions = decisions;
        this.readingQueue = readingQueue;
        this.reviewService = reviewService;
        this.accessPolicy = accessPolicy;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ---------- queries ----------

    @Override
    @Transactional(readOnly = true)
    public List<QuotationSummary> listForRequest(Long purchaseRequestId) {
        rfqs.get(purchaseRequestId); // 404 for an unknown RFQ
        return quotationRepository.findByPurchaseRequestIdOrderByIdAsc(purchaseRequestId).stream()
                .map(q -> QuotationMapper.quotationSummary(q, fileRepository.countByQuotationId(q.getId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationResponse get(Long id) {
        return toResponse(find(id));
    }

    @Override
    public DownloadedFile download(Long quotationId, Long fileId) {
        find(quotationId);
        QuotationFile file = fileRepository.findByIdAndQuotationId(fileId, quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation file", fileId));
        byte[] bytes = documents.content(file.getDocumentId());
        auditService.record(AUDIT_TYPE, quotationId, "FILE_DOWNLOADED", details("fileName", file.getFileName()));
        return new DownloadedFile(file.getFileName(), file.getContentType(), file.getSizeBytes(), bytes);
    }

    // ---------- upload and reading ----------

    @Override
    public QuotationResponse upload(Long purchaseRequestId, Long vendorId, List<MultipartFile> files,
                                    ExtractionChoice choice) {
        decisions.checkEngine(choice);
        PurchaseRequestResponse rfq = rfqs.get(purchaseRequestId);
        accessPolicy.requireModify(rfq);
        requireEditable(rfq);
        Quotation q = createQuotation(rfq, vendorRef(vendorId), files, UserRef.of(currentUser.requirePrincipal()),
                choice, false);
        return toResponse(q, rfq);
    }

    @Override
    public Quotation submitForVendor(PurchaseRequestResponse rfq, NamedRef vendor, List<MultipartFile> files) {
        return createQuotation(rfq, vendor, files, UserRef.of(currentUser.requirePrincipal()), ExtractionChoice.AUTO, true);
    }

    /** Creates a quotation with its files and queues reading. The caller has checked who may upload. */
    private Quotation createQuotation(PurchaseRequestResponse rfq, NamedRef vendor, List<MultipartFile> files,
                                      UserRef user, ExtractionChoice choice, boolean submittedByVendor) {
        requireAcceptsQuotations(rfq);
        if (!quotationRepository.findByPurchaseRequestIdAndVendorId(rfq.id(), vendor.getId()).isEmpty()) {
            throw new DuplicateResourceException(vendor.getName() + " already has a quotation on "
                    + rfq.requestNumber() + ". Add the new files to that quotation instead of creating a second one.");
        }
        List<IncomingFile> incoming = checkFiles(files, rfq.id(), 0);
        Quotation q = new Quotation();
        q.setPurchaseRequestId(rfq.id());
        q.setRequestNumber(rfq.requestNumber());
        q.setVendor(vendor);
        q.setStatus(QuotationStatus.UPLOADED);
        q.setUploadedBy(user);
        q.setSubmittedByVendor(submittedByVendor);
        q = quotationRepository.save(q);
        List<QuotationFile> stored = storeFiles(q, incoming, user);
        auditService.record(AUDIT_TYPE, q.getId(), "UPLOADED", details("purchaseRequest", rfq.requestNumber(),
                "vendor", vendor.getName(), "files", fileSummary(stored), "engine", choice.describe(),
                "submittedByVendor", submittedByVendor));
        readingQueue.queue(q.getId(), choice);
        return q;
    }

    @Override
    public QuotationResponse addFiles(Long quotationId, List<MultipartFile> files) {
        Editable e = findEditable(quotationId, false);
        requireAcceptsQuotations(e.rfq());
        long existing = fileRepository.countByQuotationId(quotationId);
        List<IncomingFile> incoming = checkFiles(files, e.rfq().id(), existing);
        List<QuotationFile> stored = storeFiles(e.quotation(), incoming, UserRef.of(currentUser.requirePrincipal()));
        requeue(e.quotation(), ExtractionChoice.AUTO);
        auditService.record(AUDIT_TYPE, quotationId, "FILES_ADDED", details("files", fileSummary(stored)));
        return toResponse(e.quotation(), e.rfq());
    }

    @Override
    public QuotationResponse removeFile(Long quotationId, Long fileId) {
        Editable e = findEditable(quotationId, false);
        QuotationFile file = fileRepository.findByIdAndQuotationId(fileId, quotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation file", fileId));
        if (fileRepository.countByQuotationId(quotationId) <= 1) {
            throw new BusinessRuleException("LAST_FILE", "A quotation must keep at least one file; delete the quotation instead");
        }
        fileRepository.delete(file);
        fileRepository.flush();
        Long documentId = file.getDocumentId();
        AfterCommit.run(() -> documents.delete(List.of(documentId)));
        requeue(e.quotation(), ExtractionChoice.AUTO);
        auditService.record(AUDIT_TYPE, quotationId, "FILE_REMOVED", details("fileName", file.getFileName()));
        return toResponse(e.quotation(), e.rfq());
    }

    @Override
    public QuotationResponse reprocess(Long id, ExtractionChoice choice) {
        decisions.checkEngine(choice);
        Editable e = findEditable(id, true);
        requeue(e.quotation(), choice);
        auditService.record(AUDIT_TYPE, id, "REPROCESS_REQUESTED", details("attempt",
                e.quotation().getProcessingAttempts() + 1, "engine", choice.describe()));
        return toResponse(e.quotation(), e.rfq());
    }

    /** Queues reading again. Previous lines and flags are replaced when the new reading arrives. */
    private void requeue(Quotation q, ExtractionChoice choice) {
        q.setStatus(QuotationStatus.UPLOADED);
        q.setFailureReason(null);
        readingQueue.queue(q.getId(), choice);
    }

    /** An active vendor from master data, as kept on the quotation. */
    private NamedRef vendorRef(Long vendorId) {
        MasterDataClient.VendorSnapshot vendor = masterData.activeVendor(vendorId);
        return new NamedRef(vendor.id(), vendor.name());
    }

    // ---------- manual review and correction ----------

    @Override
    public QuotationResponse updateHeader(Long id, QuotationUpdateRequest request) {
        Editable e = findEditable(id, false);
        Quotation q = e.quotation();
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
        reviewService.revalidate(q, e.rfq());
        auditService.record(AUDIT_TYPE, id, "HEADER_CORRECTED", details("vendorId", request.vendorId(),
                "quotationNumber", q.getQuotationNumber(), "validUntil", q.getValidUntil(), "currency", q.getCurrency(),
                "statedGrandTotal", q.getStatedGrandTotal()));
        return toResponse(q, e.rfq());
    }

    @Override
    public QuotationResponse addItem(Long id, QuotationItemRequest request) {
        Editable e = findEditable(id, false);
        Quotation q = e.quotation();
        QuotationItem item = new QuotationItem();
        item.setLineNumber(q.nextLineNumber());
        applyItem(e.rfq(), item, request);
        q.addItem(item);
        quotationRepository.flush();
        q.getFlags().removeIf(f -> !f.isResolved() && FlagCodes.NO_ITEMS_EXTRACTED.equals(f.getCode()));
        markManual(q);
        reviewService.revalidate(q, e.rfq());
        auditService.record(AUDIT_TYPE, id, "ITEM_ADDED", itemDetails(item, e.rfq()));
        return toResponse(q, e.rfq());
    }

    @Override
    public QuotationResponse updateItem(Long id, Long itemId, QuotationItemRequest request) {
        Editable e = findEditable(id, false);
        Quotation q = e.quotation();
        QuotationItem item = findItem(q, itemId);
        var before = itemDetails(item, e.rfq());
        applyItem(e.rfq(), item, request);
        // A person has checked this line, so its reading-confidence notes are settled.
        UserRef user = UserRef.of(currentUser.requirePrincipal());
        q.getFlags().stream()
                .filter(f -> !f.isResolved() && f.getQuotationItem() == item && EXTRACTION_ITEM_CODES.contains(f.getCode()))
                .forEach(f -> f.resolve(user));
        markManual(q);
        reviewService.revalidate(q, e.rfq());
        auditService.record(AUDIT_TYPE, id, "ITEM_CORRECTED", details("before", before, "after", itemDetails(item, e.rfq())));
        return toResponse(q, e.rfq());
    }

    @Override
    public QuotationResponse deleteItem(Long id, Long itemId) {
        Editable e = findEditable(id, false);
        Quotation q = e.quotation();
        QuotationItem item = findItem(q, itemId);
        q.getFlags().removeIf(f -> f.getQuotationItem() == item);
        q.getItems().remove(item);
        quotationRepository.flush();
        markManual(q);
        reviewService.revalidate(q, e.rfq());
        auditService.record(AUDIT_TYPE, id, "ITEM_DELETED", itemDetails(item, e.rfq()));
        return toResponse(q, e.rfq());
    }

    @Override
    public QuotationResponse resolveFlag(Long id, Long flagId, String comment) {
        Editable e = findEditable(id, false);
        Quotation q = e.quotation();
        QuotationFlag flag = q.getFlags().stream().filter(f -> f.getId().equals(flagId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Review flag", flagId));
        if (flag.isResolved()) {
            return toResponse(q, e.rfq());
        }
        if (flag.getSeverity() == FlagSeverity.ERROR) {
            throw new BusinessRuleException("ERROR_FLAG_NEEDS_FIX",
                    "Errors cannot be dismissed; correct the data instead (" + flag.getMessage() + ")");
        }
        flag.resolve(UserRef.of(currentUser.requirePrincipal()));
        reviewService.updateStatus(q);
        auditService.record(AUDIT_TYPE, id, "FLAG_RESOLVED", details("flag", flag.getCode(), "field",
                flag.getFieldName(), "message", flag.getMessage(), "comment", Text.blankToNull(comment)));
        return toResponse(q, e.rfq());
    }

    @Override
    public void delete(Long id) {
        Quotation q = find(id);
        PurchaseRequestResponse rfq = rfqs.get(q.getPurchaseRequestId());
        accessPolicy.requireModify(rfq);
        if (!rfq.editable()) {
            throw new BusinessRuleException("PR_CLOSED", "Quotations of a closed request cannot be deleted");
        }
        if (q.getStatus() == QuotationStatus.PROCESSING) {
            throw new BusinessRuleException("QUOTATION_PROCESSING", "Wait for processing to finish before deleting");
        }
        if (decisions.comparisonReferences(id) > 0) {
            throw new BusinessRuleException("QUOTATION_IN_COMPARISON",
                    "This quotation is part of a saved comparison report and is kept for the audit trail");
        }
        List<Long> documentIds = fileRepository.findByQuotationIdOrderByIdAsc(id).stream()
                .map(QuotationFile::getDocumentId).toList();
        auditService.record(AUDIT_TYPE, id, "DELETED", details("purchaseRequest", q.getRequestNumber(),
                "vendor", q.getVendor().getName()));
        // Also on the request, so its timeline still shows the quotation after the row is gone.
        auditService.record(RFQ_AUDIT_TYPE, q.getPurchaseRequestId(), "QUOTATION_DELETED",
                details("quotationId", id, "vendor", q.getVendor().getName()));
        quotationRepository.delete(q); // files rows go with it (cascade); the stored bytes after commit
        AfterCommit.run(() -> documents.delete(documentIds));
    }

    // ---------- for the RFQ service ----------

    @Override
    public void revalidateForRfq(Long rfqId) {
        List<Quotation> quotations = quotationRepository.findByPurchaseRequestIdOrderByIdAsc(rfqId).stream()
                .filter(q -> q.getStatus() != QuotationStatus.PROCESSING && q.getStatus() != QuotationStatus.UPLOADED)
                .toList();
        if (quotations.isEmpty()) {
            return;
        }
        PurchaseRequestResponse rfq = rfqs.get(rfqId);
        quotations.forEach(q -> reviewService.revalidate(q, rfq));
    }

    @Override
    @Transactional(readOnly = true)
    public RfqQuotationUsage usageForRfq(Long rfqId) {
        return new RfqQuotationUsage(quotationRepository.countByPurchaseRequestId(rfqId),
                quotationRepository.findMatchedRequestItemIds(rfqId));
    }

    // ---------- helpers ----------

    @Override
    public Quotation find(Long id) {
        return quotationRepository.findById(id).orElseThrow(() -> new QuotationNotFoundException(id));
    }

    @Override
    public QuotationResponse toResponse(Quotation q) {
        return toResponse(q, rfqs.get(q.getPurchaseRequestId()));
    }

    private QuotationResponse toResponse(Quotation q, PurchaseRequestResponse rfq) {
        return QuotationMapper.quotationResponse(q, fileRepository.findByQuotationIdOrderByIdAsc(q.getId()), rfq);
    }

    /** A quotation the user may change, with its RFQ. */
    private record Editable(Quotation quotation, PurchaseRequestResponse rfq) {
    }

    private Editable findEditable(Long id, boolean allowStaleProcessing) {
        Quotation q = find(id);
        PurchaseRequestResponse rfq = rfqs.get(q.getPurchaseRequestId());
        accessPolicy.requireModify(rfq);
        requireEditable(rfq);
        requireNotProcessing(q, allowStaleProcessing);
        // Any edit to items or flags bumps the quotation version, so saved comparisons can detect stale data.
        entityManager.lock(q, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        return new Editable(q, rfq);
    }

    private static void requireEditable(PurchaseRequestResponse rfq) {
        if (!rfq.editable()) {
            throw new BusinessRuleException("PR_CLOSED", rfq.requestNumber() + " is " + rfq.status()
                    + "; its quotations can no longer change");
        }
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

    private static void requireAcceptsQuotations(PurchaseRequestResponse rfq) {
        if (!ACCEPTS_QUOTATIONS.contains(rfq.status())) {
            throw new BusinessRuleException("PR_NOT_OPEN",
                    rfq.requestNumber() + " is not accepting quotations (it is " + rfq.status() + ")");
        }
        if (rfq.items().isEmpty()) {
            throw new BusinessRuleException("PR_HAS_NO_ITEMS", "Add the requested items before uploading quotations");
        }
    }

    /** A file as uploaded, with its hash (the document service checks its content). */
    private record IncomingFile(String fileName, byte[] bytes, String sha256) {
    }

    /** Count, emptiness and duplicates; the content itself is checked by the document service when stored. */
    private List<IncomingFile> checkFiles(List<MultipartFile> files, Long purchaseRequestId, long existingCount) {
        List<MultipartFile> nonEmpty = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (nonEmpty.isEmpty()) {
            throw new InvalidQuotationFileException("No file was uploaded, or the files are empty.");
        }
        if (existingCount + nonEmpty.size() > MAX_FILES_PER_QUOTATION) {
            throw new InvalidQuotationFileException("A quotation can have at most " + MAX_FILES_PER_QUOTATION
                    + " files (it has " + existingCount + ", " + nonEmpty.size() + " more were uploaded).");
        }
        List<IncomingFile> incoming = new ArrayList<>();
        Set<String> hashes = new HashSet<>();
        for (MultipartFile multipartFile : nonEmpty) {
            String fileName = multipartFile.getOriginalFilename() == null ? "file" : multipartFile.getOriginalFilename();
            byte[] bytes = readBytes(multipartFile);
            String sha256 = sha256(bytes);
            if (!hashes.add(sha256)) {
                throw new DuplicateResourceException("'" + displayName(fileName) + "' was selected twice");
            }
            if (quotationRepository.existsFileInRequest(sha256, purchaseRequestId)) {
                throw new DuplicateResourceException("'" + displayName(fileName)
                        + "' was already uploaded for this purchase request");
            }
            incoming.add(new IncomingFile(fileName, bytes, sha256));
        }
        return incoming;
    }

    /** Stores the bytes in the document service (which checks the content) and keeps the references. */
    private List<QuotationFile> storeFiles(Quotation q, List<IncomingFile> files, UserRef user) {
        List<StoredDocument> stored = documents.store(
                files.stream().map(f -> new DocumentStoreClient.Upload(f.fileName(), f.bytes())).toList(),
                user.getId(), user.getUsername());
        List<QuotationFile> rows = new ArrayList<>();
        for (StoredDocument d : stored) {
            QuotationFile file = new QuotationFile();
            file.setQuotation(q);
            file.setDocumentId(d.id());
            file.setFileName(d.fileName());
            file.setContentType(d.contentType());
            file.setFileFormat(d.fileFormat());
            file.setSizeBytes(d.sizeBytes());
            file.setSha256(d.sha256());
            file.setUploadedBy(user);
            file.setUploadedAt(Instant.now(clock));
            rows.add(fileRepository.save(file));
        }
        List<Long> documentIds = stored.stream().map(StoredDocument::id).toList();
        Long quotationId = q.getId();
        AfterCommit.run(() -> documents.attach(documentIds, quotationId));
        return rows;
    }

    private static List<Map<String, Object>> fileSummary(List<QuotationFile> files) {
        return files.stream()
                .map(f -> details("fileName", f.getFileName(), "format", f.getFileFormat(), "sizeBytes", f.getSizeBytes()))
                .toList();
    }

    private static QuotationItem findItem(Quotation q, Long itemId) {
        return q.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Quotation item", itemId));
    }

    private static void applyItem(PurchaseRequestResponse rfq, QuotationItem item, QuotationItemRequest request) {
        if (request.requestItemId() != null && rfq.items().stream().noneMatch(r -> r.id().equals(request.requestItemId()))) {
            throw new BusinessRuleException("REQUEST_ITEM_MISMATCH",
                    "Request item " + request.requestItemId() + " does not belong to this purchase request");
        }
        if (!Objects.equals(item.getRequestItemId(), request.requestItemId())) {
            // A person chose the match, so it is certain.
            item.setMatchConfidence(request.requestItemId() == null ? null : BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP));
        }
        item.setRequestItemId(request.requestItemId());
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

    /** A failed or unread quotation that a person fills in becomes a manual entry. */
    private static void markManual(Quotation q) {
        if (q.getExtractionSource() == null) {
            q.setExtractionSource(ExtractionSource.MANUAL);
        }
        if (q.getStatus() == QuotationStatus.FAILED || q.getStatus() == QuotationStatus.UPLOADED) {
            q.setStatus(QuotationStatus.NEEDS_REVIEW);
            q.setFailureReason(null);
        }
    }

    private static Map<String, Object> itemDetails(QuotationItem i, PurchaseRequestResponse rfq) {
        Integer requestLine = i.getRequestItemId() == null ? null : rfq.items().stream()
                .filter(r -> r.id().equals(i.getRequestItemId())).map(RequestItemResponse::lineNumber)
                .findFirst().orElse(null);
        return details("line", i.getLineNumber(), "itemCode", i.getItemCode(), "description", i.getDescription(),
                "quantity", i.getQuantity(), "unitPrice", i.getUnitPrice(), "discountPercent", i.getDiscountPercent(),
                "taxPercent", i.getTaxPercent(), "lineTotal", i.getLineTotal(), "requestLine", requestLine);
    }

    private static String displayName(String name) {
        String base = name.replace('\\', '/');
        return base.substring(base.lastIndexOf('/') + 1);
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
}
