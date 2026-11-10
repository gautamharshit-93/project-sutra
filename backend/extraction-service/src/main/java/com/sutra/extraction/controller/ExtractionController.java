package com.sutra.extraction.controller;

import com.sutra.api.rfq.PurchaseRequestRequests.RfqTextRequest;
import com.sutra.api.rfq.RfqDraftResponses.RfqDraft;
import com.sutra.common.security.Roles;
import com.sutra.extraction.dto.response.ExtractionJobResponse;
import com.sutra.extraction.service.ExtractionService;
import com.sutra.extraction.service.RfqDraftService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Reading status, and RFQ drafts read from a dropped document or pasted text (nothing is saved). */
@RestController
@RequestMapping("/api")
@PreAuthorize(Roles.STAFF)
public class ExtractionController {

    private final ExtractionService extractions;
    private final RfqDraftService drafts;

    public ExtractionController(ExtractionService extractions, RfqDraftService drafts) {
        this.extractions = extractions;
        this.drafts = drafts;
    }

    /** The status URL returned when a quotation is uploaded or read again. */
    @GetMapping("/extractions/{id}")
    public ExtractionJobResponse get(@PathVariable Long id) {
        return extractions.get(id);
    }

    /** Every reading of one quotation, newest first: engine, model, duration, result. */
    @GetMapping("/extractions")
    public List<ExtractionJobResponse> history(@RequestParam Long quotationId) {
        return extractions.history(quotationId);
    }

    @PostMapping(path = "/purchase-requests/draft-from-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.BUYER)
    public RfqDraft draftFromFiles(@RequestPart("files") List<MultipartFile> files) {
        return drafts.fromFiles(files);
    }

    @PostMapping("/purchase-requests/draft-from-text")
    @PreAuthorize(Roles.BUYER)
    public RfqDraft draftFromText(@Valid @RequestBody RfqTextRequest request) {
        return drafts.fromText(request.text());
    }
}
