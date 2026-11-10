package com.sutra.quotation.service.impl;

import com.sutra.api.enums.FlagSeverity;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.extraction.ExtractedQuotation;
import com.sutra.api.extraction.ExtractedQuotation.ExtractedItem;
import com.sutra.api.internal.InternalContracts.ExtractionOutcome;
import com.sutra.api.internal.InternalContracts.ExtractionWork;
import com.sutra.api.internal.InternalContracts.LineMatch;
import com.sutra.api.internal.InternalContracts.RequestedLine;
import com.sutra.api.internal.InternalContracts.WorkDocument;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import com.sutra.api.rules.FlagCodes;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.quotation.client.RfqClient;
import com.sutra.quotation.config.QuotationProperties;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationFile;
import com.sutra.quotation.entity.QuotationFlag;
import com.sutra.quotation.entity.QuotationItem;
import com.sutra.quotation.exception.QuotationNotFoundException;
import com.sutra.quotation.repository.QuotationFileRepository;
import com.sutra.quotation.repository.QuotationRepository;
import com.sutra.quotation.service.ExtractionResultService;
import com.sutra.quotation.service.internal.QuotationReviewService;
import com.sutra.quotation.service.internal.TextSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.sutra.common.audit.AuditService.details;

/**
 * The quotation side of reading (the extraction service does the reading itself):
 * <ol>
 *   <li>{@link #claim}: marks the quotation PROCESSING and returns its files and the RFQ's requested lines.</li>
 *   <li>{@link #receive}: stores the lines read (replacing earlier ones) with their matches, recalculates every
 *       total in Java, raises the review flags and sets READY_FOR_COMPARISON or NEEDS_REVIEW; or, if reading
 *       failed, FAILED with the reason (the files are untouched, so the user can retry or type the lines).</li>
 * </ol>
 */
@Service
@Transactional
public class ExtractionResultServiceImpl implements ExtractionResultService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionResultServiceImpl.class);

    private final QuotationRepository quotationRepository;
    private final QuotationFileRepository fileRepository;
    private final RfqClient rfqs;
    private final QuotationReviewService reviewService;
    private final AuditService auditService;
    private final BigDecimal reviewThreshold;
    private final Clock clock;

    public ExtractionResultServiceImpl(QuotationRepository quotationRepository, QuotationFileRepository fileRepository,
                                       RfqClient rfqs, QuotationReviewService reviewService, AuditService auditService,
                                       QuotationProperties properties, Clock clock) {
        this.quotationRepository = quotationRepository;
        this.fileRepository = fileRepository;
        this.rfqs = rfqs;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.reviewThreshold = properties.reviewConfidenceThreshold();
        this.clock = clock;
    }

    @Override
    public ExtractionWork claim(Long quotationId) {
        Quotation q = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));
        if (q.getStatus() == QuotationStatus.PROCESSING) {
            throw new DuplicateResourceException("Quotation " + quotationId + " is already being read");
        }
        List<QuotationFile> files = fileRepository.findByQuotationIdOrderByIdAsc(quotationId);
        PurchaseRequestResponse rfq = rfqs.get(q.getPurchaseRequestId());
        q.setStatus(QuotationStatus.PROCESSING);
        q.setProcessingAttempts(q.getProcessingAttempts() + 1);
        q.setFailureReason(null);
        List<WorkDocument> documents = files.stream()
                .map(f -> new WorkDocument(f.getDocumentId(), f.getFileName(), f.getFileFormat())).toList();
        List<RequestedLine> lines = rfq.items().stream()
                .map(i -> new RequestedLine(i.id(), i.lineNumber(), i.itemCode(), i.description(), i.quantity(),
                        i.unitOfMeasure(), i.specifications()))
                .toList();
        return new ExtractionWork(quotationId, q.getVendor().getName(), rfq.currency(), documents, lines);
    }

    @Override
    public void receive(Long quotationId, ExtractionOutcome outcome) {
        Quotation q = quotationRepository.findById(quotationId).orElse(null);
        if (q == null) {
            log.warn("Quotation {} disappeared before its reading arrived", quotationId);
            return;
        }
        if (!outcome.succeeded()) {
            fail(q, outcome.failureReason() == null ? "The quotation could not be read" : outcome.failureReason());
            return;
        }
        PurchaseRequestResponse rfq = rfqs.get(q.getPurchaseRequestId());
        // A (re)reading replaces all previous lines and flags.
        q.getFlags().clear();
        q.getItems().clear();
        quotationRepository.flush();
        populate(q, rfq, outcome);
        quotationRepository.flush(); // assigns item ids before flagging
        q.setLastProcessedAt(Instant.now(clock));
        reviewService.revalidate(q, rfq);
        auditService.recordAs(CurrentUserProvider.SYSTEM_USER, QuotationServiceImpl.AUDIT_TYPE, quotationId, "EXTRACTED",
                details("source", outcome.source(), "model", outcome.model(), "items", q.getItems().size(),
                        "confidence", q.getExtractionConfidence(), "status", q.getStatus(), "openFlags", q.openFlagCount()));
    }

    private void fail(Quotation q, String reason) {
        q.setStatus(QuotationStatus.FAILED);
        q.setFailureReason(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
        q.setLastProcessedAt(Instant.now(clock));
        auditService.recordAs(CurrentUserProvider.SYSTEM_USER, QuotationServiceImpl.AUDIT_TYPE, q.getId(),
                "EXTRACTION_FAILED", details("reason", q.getFailureReason(), "attempt", q.getProcessingAttempts()));
        log.warn("Reading failed for quotation {}: {}", q.getId(), reason);
    }

    private void populate(Quotation q, PurchaseRequestResponse rfq, ExtractionOutcome outcome) {
        ExtractedQuotation data = outcome.data();
        applyHeader(q, data);
        Set<Long> requestIds = rfq.items().stream().map(RequestItemResponse::id).collect(Collectors.toSet());
        Map<Integer, LineMatch> matchByIndex = new HashMap<>();
        for (LineMatch m : outcome.matches() == null ? List.<LineMatch>of() : outcome.matches()) {
            if (m.requestItemId() != null && requestIds.contains(m.requestItemId())) {
                matchByIndex.put(m.index(), m);
            }
        }
        int line = 1;
        for (int index = 0; index < data.items().size(); index++) {
            ExtractedItem extracted = data.items().get(index);
            if (extracted.description() == null || extracted.description().isBlank()) {
                q.addFlag(new QuotationFlag(null, "items", FlagSeverity.WARNING, FlagCodes.EXTRACTOR_NOTE,
                        "A priced line without a description was skipped; check the document"));
                continue;
            }
            QuotationItem item = toItem(line++, extracted);
            LineMatch match = matchByIndex.get(index);
            if (match != null) {
                item.setRequestItemId(match.requestItemId());
                item.setMatchConfidence(match.score() == null ? null : match.score().setScale(3, RoundingMode.HALF_UP));
            }
            q.addItem(item);
        }
        addExtractionFlags(q, data);
        q.setExtractionSource(outcome.source());
        q.setExtractionConfidence(confidence(data.overallConfidence()));
        q.setStatus(QuotationStatus.NEEDS_REVIEW);
    }

    private void applyHeader(Quotation q, ExtractedQuotation data) {
        q.setQuotationNumber(truncate(data.quotationNumber().orElse(null), 60));
        q.setQuotationDate(parseDate(q, "quotationDate", data.quotationDate()));
        LocalDate validUntil = parseDate(q, "validUntil", data.validUntil());
        if (validUntil != null && q.getQuotationDate() != null && validUntil.isBefore(q.getQuotationDate())) {
            q.addFlag(new QuotationFlag(null, "validUntil", FlagSeverity.WARNING, FlagCodes.EXTRACTOR_NOTE,
                    "Extracted validity date " + validUntil + " is before the quotation date and was ignored"));
            validUntil = null;
        }
        q.setValidUntil(validUntil);
        q.setCurrency(data.currency().map(c -> c.trim().toUpperCase(Locale.ROOT))
                .filter(c -> c.matches("^[A-Z]{3}$")).orElse(null));
        q.setStatedSubtotal(money(data.subtotal()));
        q.setStatedTaxTotal(money(data.taxTotal()));
        q.setStatedGrandTotal(money(data.grandTotal()));
        q.setDeliveryTerms(data.deliveryTerms().orElse(null));
        q.setPaymentTerms(data.paymentTerms().orElse(null));
        q.setWarrantyTerms(data.warrantyTerms().orElse(null));
        q.setNotes(data.notes().orElse(null));
    }

    private static QuotationItem toItem(int line, ExtractedItem e) {
        QuotationItem item = new QuotationItem();
        item.setLineNumber(line);
        item.setItemCode(truncate(e.itemCode().orElse(null), 40));
        item.setDescription(truncate(e.description().trim(), 500));
        item.setQuantity(positive(e.quantity(), 3));
        item.setUnitOfMeasure(truncate(e.unitOfMeasure().orElse(null), 30));
        item.setUnitPrice(nonNegative(e.unitPrice(), 4));
        item.setDiscountPercent(percent(e.discountPercent()));
        item.setDiscountAmount(nonNegative(e.discountAmount(), 2));
        item.setTaxPercent(percent(e.taxPercent()));
        item.setTaxAmount(nonNegative(e.taxAmount(), 2));
        item.setStatedLineTotal(nonNegative(e.lineTotal(), 2));
        item.setExtractionConfidence(confidence(e.confidence()));
        item.setNotes(e.notes().orElse(null));
        return item;
    }

    private void addExtractionFlags(Quotation q, ExtractedQuotation data) {
        if (q.getItems().isEmpty()) {
            q.addFlag(new QuotationFlag(null, "items", FlagSeverity.ERROR, FlagCodes.NO_ITEMS_EXTRACTED,
                    "No line items could be extracted. Add them manually."));
        }
        for (QuotationItem item : q.getItems()) {
            if (item.getExtractionConfidence() != null && item.getExtractionConfidence().compareTo(reviewThreshold) < 0) {
                q.addFlag(new QuotationFlag(item, "item", FlagSeverity.WARNING, FlagCodes.LOW_CONFIDENCE,
                        "Line " + item.getLineNumber() + " was read with low confidence ("
                                + item.getExtractionConfidence() + "); compare it with the original document"));
            }
        }
        for (String warning : data.warnings()) {
            if (warning != null && !warning.isBlank()) {
                q.addFlag(new QuotationFlag(null, "document", FlagSeverity.WARNING, FlagCodes.EXTRACTOR_NOTE,
                        "Extractor note: " + warning.trim()));
            }
        }
        data.vendorName().filter(name -> TextSimilarity.score(name, q.getVendor().getName()) < 0.5)
                .ifPresent(name -> q.addFlag(new QuotationFlag(null, "vendor", FlagSeverity.WARNING,
                        FlagCodes.VENDOR_NAME_MISMATCH, "The document names the vendor \"" + name
                        + "\", but it was uploaded for \"" + q.getVendor().getName() + "\"")));
    }

    private LocalDate parseDate(Quotation q, String field, Optional<String> value) {
        if (value.isEmpty() || value.get().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.get().trim());
        } catch (DateTimeParseException e) {
            q.addFlag(new QuotationFlag(null, field, FlagSeverity.WARNING, FlagCodes.EXTRACTOR_NOTE,
                    "Could not read the " + field + " value '" + truncate(value.get(), 40) + "'; enter it manually"));
            return null;
        }
    }

    private static BigDecimal money(Optional<BigDecimal> value) {
        return nonNegative(value, 2);
    }

    private static BigDecimal positive(Optional<BigDecimal> value, int scale) {
        return value.filter(v -> v.signum() > 0).map(v -> v.setScale(scale, RoundingMode.HALF_UP)).orElse(null);
    }

    private static BigDecimal nonNegative(Optional<BigDecimal> value, int scale) {
        return value.filter(v -> v.signum() >= 0).map(v -> v.setScale(scale, RoundingMode.HALF_UP)).orElse(null);
    }

    private static BigDecimal percent(Optional<BigDecimal> value) {
        return value.filter(v -> v.signum() >= 0 && v.compareTo(BigDecimal.valueOf(100)) <= 0)
                .map(v -> v.setScale(4, RoundingMode.HALF_UP)).orElse(null);
    }

    private static BigDecimal confidence(double value) {
        double clamped = Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
        return BigDecimal.valueOf(clamped).setScale(3, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t.length() <= max ? t : t.substring(0, max);
    }
}
