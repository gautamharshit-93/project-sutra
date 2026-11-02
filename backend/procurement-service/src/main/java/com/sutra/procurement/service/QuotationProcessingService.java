package com.sutra.procurement.service;

import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.procurement.ai.ExtractedQuotation;
import com.sutra.procurement.ai.ExtractedQuotation.ExtractedItem;
import com.sutra.procurement.ai.ExtractionChoice;
import com.sutra.procurement.ai.ExtractionEngine;
import com.sutra.procurement.ai.ExtractionEngines;
import com.sutra.procurement.ai.ExtractionInput;
import com.sutra.procurement.ai.ItemMatcher;
import com.sutra.procurement.calculation.FlagCodes;
import com.sutra.procurement.config.SutraProperties;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationFile;
import com.sutra.procurement.entity.QuotationFlag;
import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.enums.ExtractionSource;
import com.sutra.procurement.entity.enums.FlagSeverity;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.exception.QuotationNotFoundException;
import com.sutra.procurement.exception.QuotationProcessingException;
import com.sutra.procurement.repository.QuotationFileRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.procurement.util.TextSimilarity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs extraction for one quotation (all of its files together):
 * <ol>
 *   <li>Claim it (status PROCESSING) in a short transaction.</li>
 *   <li>Call the chosen extraction engine (or AUTO's first engine that succeeds) outside any transaction.</li>
 *   <li>Store items and flags, match lines to the request, recalculate, and set NEEDS_REVIEW or
 *       READY_FOR_COMPARISON in a second transaction.</li>
 * </ol>
 * On failure the quotation becomes FAILED with a reason; the original file is untouched so the user can retry or
 * enter the data manually.
 */
@Service
public class QuotationProcessingService {

    private static final Logger log = LoggerFactory.getLogger(QuotationProcessingService.class);

    private final QuotationRepository quotationRepository;
    private final QuotationFileRepository fileRepository;
    private final ExtractionEngines engines;
    private final ItemMatcher itemMatcher;
    private final QuotationReviewService reviewService;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final BigDecimal reviewThreshold;
    private final Clock clock;

    public QuotationProcessingService(QuotationRepository quotationRepository, QuotationFileRepository fileRepository,
                                      ExtractionEngines engines, ItemMatcher itemMatcher,
                                      QuotationReviewService reviewService, AuditService auditService,
                                      PlatformTransactionManager transactionManager, SutraProperties properties,
                                      Clock clock) {
        this.quotationRepository = quotationRepository;
        this.fileRepository = fileRepository;
        this.engines = engines;
        this.itemMatcher = itemMatcher;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.tx = new TransactionTemplate(transactionManager);
        this.reviewThreshold = properties.ai().reviewConfidenceThreshold();
        this.clock = clock;
    }

    public void process(Long quotationId) {
        process(quotationId, ExtractionEngine.AUTO);
    }

    public void process(Long quotationId, ExtractionEngine engine) {
        process(quotationId, ExtractionChoice.of(engine));
    }

    public void process(Long quotationId, ExtractionChoice choice) {
        ExtractionEngine engine = choice.engine();
        ExtractionInput input;
        try {
            input = tx.execute(status -> claim(quotationId));
            if (input != null) {
                input = input.withGeminiModel(choice.geminiModel());
            }
        } catch (QuotationNotFoundException e) {
            log.warn("Quotation {} disappeared before processing", quotationId);
            return;
        }
        if (input == null) {
            return; // already being processed
        }

        ExtractionEngines.Extraction extracted;
        try {
            extracted = engines.extract(input, engine);
        } catch (QuotationProcessingException e) {
            fail(quotationId, e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.error("Unexpected extraction error for quotation {}", quotationId, e);
            fail(quotationId, "Unexpected extraction error. Retry, or enter the quotation manually.");
            return;
        }

        try {
            tx.executeWithoutResult(status -> store(quotationId, extracted.data(), extracted.source()));
        } catch (RuntimeException e) {
            log.error("Could not store extraction result for quotation {}", quotationId, e);
            fail(quotationId, "Extracted data could not be saved: " + e.getClass().getSimpleName()
                    + ". Retry, or enter the quotation manually.");
        }
    }

    /** Marks the quotation PROCESSING and collects what the extractor needs. Returns null if already claimed. */
    private ExtractionInput claim(Long quotationId) {
        Quotation q = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));
        if (q.getStatus() == QuotationStatus.PROCESSING) {
            return null;
        }
        List<QuotationFile> files = fileRepository.findByQuotationIdOrderByIdAsc(quotationId);
        if (files.isEmpty()) {
            throw new QuotationProcessingException("The original file is missing");
        }
        q.setStatus(QuotationStatus.PROCESSING);
        q.setProcessingAttempts(q.getProcessingAttempts() + 1);
        q.setFailureReason(null);
        return inputFor(q, files);
    }

    /** What the extractor needs for a quotation: its files, vendor and the requested lines. Call in a transaction. */
    ExtractionInput inputFor(Quotation q, List<QuotationFile> files) {
        List<ExtractionInput.RequestedItem> requested = q.getPurchaseRequest().getItems().stream()
                .map(i -> new ExtractionInput.RequestedItem(i.getLineNumber(), i.getItemCode(), i.getDescription(),
                        i.getQuantity(), i.getUnitOfMeasure(), i.getSpecifications()))
                .toList();
        List<ExtractionInput.Document> documents = files.stream()
                .map(f -> new ExtractionInput.Document(f.getFileName(), f.getFileFormat(), f.getContent()))
                .toList();
        return new ExtractionInput(documents, q.getVendor().getName(), q.getPurchaseRequest().getCurrency(), requested);
    }

    private void fail(Long quotationId, String reason) {
        tx.executeWithoutResult(status -> quotationRepository.findById(quotationId).ifPresent(q -> {
            q.setStatus(QuotationStatus.FAILED);
            q.setFailureReason(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
            q.setLastProcessedAt(Instant.now(clock));
            auditService.recordAs(CurrentUserProvider.SYSTEM_USER, QuotationService.AUDIT_TYPE, quotationId,
                    "EXTRACTION_FAILED", details("reason", q.getFailureReason(), "attempt", q.getProcessingAttempts()));
        }));
        log.warn("Extraction failed for quotation {}: {}", quotationId, reason);
    }

    void store(Long quotationId, ExtractedQuotation data, ExtractionSource source) {
        Quotation q = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));

        // A (re)extraction replaces all previous lines and flags.
        q.getFlags().clear();
        q.getItems().clear();
        quotationRepository.flush();

        populate(q, data, source, quotationRepository::flush); // flush assigns item ids before matching and flagging
        q.setLastProcessedAt(Instant.now(clock));
        reviewService.revalidate(q);

        auditService.recordAs(CurrentUserProvider.SYSTEM_USER, QuotationService.AUDIT_TYPE, quotationId, "EXTRACTED",
                details("source", source, "items", q.getItems().size(), "confidence", q.getExtractionConfidence(),
                        "status", q.getStatus(), "openFlags", q.openFlagCount()));
    }

    /**
     * Builds an unsaved copy of the quotation from one engine's extraction - matched, recalculated and flagged
     * exactly as a stored extraction would be - so engines can be cross-checked without changing anything.
     * Call inside a (read-only) transaction; the copy must never be saved.
     */
    Quotation preview(Quotation original, ExtractedQuotation data, ExtractionSource source) {
        Quotation copy = new Quotation();
        copy.setPurchaseRequest(original.getPurchaseRequest());
        copy.setVendor(original.getVendor());
        populate(copy, data, source, () -> { });
        reviewService.revalidate(copy);
        return copy;
    }

    private void populate(Quotation q, ExtractedQuotation data, ExtractionSource source, Runnable afterItems) {
        applyHeader(q, data);
        int line = 1;
        for (ExtractedItem extractedItem : data.items()) {
            if (extractedItem.description() == null || extractedItem.description().isBlank()) {
                q.addFlag(new QuotationFlag(null, "items", FlagSeverity.WARNING, FlagCodes.EXTRACTOR_NOTE,
                        "A priced line without a description was skipped; check the document"));
                continue;
            }
            q.addItem(toItem(q, line++, extractedItem));
        }
        afterItems.run();

        itemMatcher.assign(q.getPurchaseRequest().getItems(), q.getItems());
        addExtractionFlags(q, data);

        q.setExtractionSource(source);
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

    private QuotationItem toItem(Quotation q, int line, ExtractedItem e) {
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
