package com.sutra.quotation.controller;

import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.internal.InternalContracts.ExtractionOutcome;
import com.sutra.api.internal.InternalContracts.ExtractionWork;
import com.sutra.api.internal.InternalContracts.RfqQuotationUsage;
import com.sutra.api.internal.InternalContracts.StatusCounts;
import com.sutra.api.internal.InternalContracts.Usage;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.quotation.repository.QuotationRepository;
import com.sutra.quotation.service.ExtractionResultService;
import com.sutra.quotation.service.QuotationService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Quotation data and reading results for the other services (service tokens only). */
@RestController
@RequestMapping("/internal")
public class InternalQuotationController {

    private final QuotationService quotations;
    private final ExtractionResultService extraction;
    private final QuotationRepository repository;

    public InternalQuotationController(QuotationService quotations, ExtractionResultService extraction,
                                       QuotationRepository repository) {
        this.quotations = quotations;
        this.extraction = extraction;
        this.repository = repository;
    }

    // ---------- the extraction service ----------

    @PostMapping("/quotations/{id}/extraction/claim")
    public ExtractionWork claim(@PathVariable Long id) {
        return extraction.claim(id);
    }

    @PostMapping("/quotations/{id}/extraction/result")
    public ResponseEntity<Void> result(@PathVariable Long id, @RequestBody ExtractionOutcome outcome) {
        extraction.receive(id, outcome);
        return ResponseEntity.noContent().build();
    }

    // ---------- the RFQ service ----------

    @GetMapping("/rfqs/{rfqId}/quotation-usage")
    public RfqQuotationUsage usageForRfq(@PathVariable Long rfqId) {
        return quotations.usageForRfq(rfqId);
    }

    @PostMapping("/rfqs/{rfqId}/revalidate")
    public ResponseEntity<Void> revalidate(@PathVariable Long rfqId) {
        quotations.revalidateForRfq(rfqId);
        return ResponseEntity.noContent().build();
    }

    // ---------- comparison and approval ----------

    /** Every quotation of the RFQ, in full (the comparison service compares the ready ones). */
    @GetMapping("/rfqs/{rfqId}/quotations")
    @Transactional(readOnly = true)
    public List<QuotationResponse> forRfq(@PathVariable Long rfqId) {
        return repository.findByPurchaseRequestIdOrderByIdAsc(rfqId).stream().map(quotations::toResponse).toList();
    }

    @GetMapping("/quotations/{id}")
    @Transactional(readOnly = true)
    public QuotationResponse get(@PathVariable Long id) {
        return quotations.toResponse(quotations.find(id));
    }

    /** Current versions (optimistic-lock counters), to tell whether a saved comparison is stale. */
    @GetMapping("/quotations/versions")
    @Transactional(readOnly = true)
    public Map<Long, Long> versions(@RequestParam List<Long> ids) {
        Map<Long, Long> versions = new LinkedHashMap<>();
        repository.findAllById(ids).forEach(q -> versions.put(q.getId(), q.getVersion()));
        return versions;
    }

    // ---------- reporting, master data and users ----------

    @GetMapping("/quotations/stats")
    @Transactional(readOnly = true)
    public StatusCounts stats() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (QuotationStatus s : QuotationStatus.values()) {
            counts.put(s.name(), repository.countByStatus(s));
        }
        return new StatusCounts(counts);
    }

    /** Of the given RFQs, those with at least one quotation ready to compare (menu badges). */
    @GetMapping("/rfqs/with-ready-quotations")
    @Transactional(readOnly = true)
    public List<Long> withReadyQuotations(@RequestParam List<Long> rfqIds) {
        return rfqIds.isEmpty() ? List.of() : repository.findRfqIdsWithReadyQuotations(rfqIds);
    }

    /** A vendor's quotations and their RFQs (menu badges). */
    @GetMapping("/vendors/{vendorId}/quotation-refs")
    @Transactional(readOnly = true)
    public List<com.sutra.api.internal.InternalContracts.QuotationRef> quotationsOf(@PathVariable Long vendorId) {
        return repository.findByVendorIdOrderByIdDesc(vendorId).stream()
                .map(q -> new com.sutra.api.internal.InternalContracts.QuotationRef(q.getId(), q.getPurchaseRequestId()))
                .toList();
    }

    /** {@code kind}: vendors or users. */
    @GetMapping("/usage/{kind}/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Usage> usage(@PathVariable String kind, @PathVariable Long id) {
        return switch (kind) {
            case "vendors" -> ResponseEntity.ok(new Usage(0, repository.countByVendorId(id)));
            case "users" -> ResponseEntity.ok(new Usage(0, repository.countByUploadedById(id)));
            default -> ResponseEntity.notFound().build();
        };
    }

    /** An RFQ's quotations as its quotation list shows them (reporting: the assistant). */
    @GetMapping("/rfqs/{rfqId}/quotation-summaries")
    @Transactional(readOnly = true)
    public List<com.sutra.api.quotation.QuotationResponses.QuotationSummary> summaries(@PathVariable Long rfqId) {
        return quotations.listForRequest(rfqId);
    }
}
