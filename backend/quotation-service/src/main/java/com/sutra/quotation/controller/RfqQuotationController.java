package com.sutra.quotation.controller;

import com.sutra.api.enums.ExtractionEngine;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationSummary;
import com.sutra.common.security.Roles;
import com.sutra.quotation.service.QuotationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** An RFQ's quotations: list them, or upload a vendor's e-mailed quotation (the gateway routes these paths here). */
@RestController
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/purchase-requests/{id}/quotations")
public class RfqQuotationController {

    private final QuotationService quotationService;

    public RfqQuotationController(QuotationService quotationService) {
        this.quotationService = quotationService;
    }

    @GetMapping
    public List<QuotationSummary> quotations(@PathVariable Long id) {
        return quotationService.listForRequest(id);
    }

    /** One quotation may have several files (e.g. the same offer as PDF, Word and Excel); they are read together. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Roles.BUYER)
    public QuotationResponse upload(@PathVariable Long id, @RequestParam Long vendorId,
                                    @RequestPart("files") List<MultipartFile> files,
                                    @RequestParam(defaultValue = "AUTO") ExtractionEngine engine,
                                    @RequestParam(required = false) String model) {
        return quotationService.upload(id, vendorId, files, new ExtractionChoice(engine, model));
    }
}
