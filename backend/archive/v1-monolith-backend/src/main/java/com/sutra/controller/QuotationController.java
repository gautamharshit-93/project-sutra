package com.sutra.controller;

import com.sutra.ai.ExtractionEngine;
import com.sutra.entity.QuotationFile;
import com.sutra.request.QuotationRequests.QuotationItemRequest;
import com.sutra.request.QuotationRequests.QuotationUpdateRequest;
import com.sutra.request.QuotationRequests.ResolveFlagRequest;
import com.sutra.response.DecisionResponses.AuditLogResponse;
import com.sutra.response.PageResponse;
import com.sutra.response.CrossCheckResponses.CrossCheckResponse;
import com.sutra.response.QuotationResponses.QuotationResponse;
import com.sutra.service.ExtractionCrossCheckService;
import com.sutra.service.QuotationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/quotations/{id}")
public class QuotationController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN','PROCUREMENT')";

    private final QuotationService quotationService;
    private final ExtractionCrossCheckService crossCheckService;

    public QuotationController(QuotationService quotationService, ExtractionCrossCheckService crossCheckService) {
        this.quotationService = quotationService;
        this.crossCheckService = crossCheckService;
    }

    @GetMapping
    public QuotationResponse get(@PathVariable Long id) {
        return quotationService.get(id);
    }

    /** Streams one original uploaded file. */
    @GetMapping("/history")
    public PageResponse<AuditLogResponse> history(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "100") int size) {
        return quotationService.history(id, page, size);
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long fileId) {
        QuotationFile file = quotationService.download(id, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(file.getSizeBytes())
                .body(file.getContent());
    }

    @PutMapping
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse updateHeader(@PathVariable Long id, @Valid @RequestBody QuotationUpdateRequest request) {
        return quotationService.updateHeader(id, request);
    }

    @DeleteMapping
    @PreAuthorize(CAN_EDIT)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        quotationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Adds more files to the quotation; extraction re-runs over all of its files. */
    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse addFiles(@PathVariable Long id, @RequestPart("files") List<MultipartFile> files) {
        return quotationService.addFiles(id, files);
    }

    /** Removes one file (a quotation keeps at least one); extraction re-runs. */
    @DeleteMapping("/files/{fileId}")
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse removeFile(@PathVariable Long id, @PathVariable Long fileId) {
        return quotationService.removeFile(id, fileId);
    }

    @PostMapping("/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse reprocess(@PathVariable Long id,
                                       @RequestParam(defaultValue = "AUTO") ExtractionEngine engine) {
        return quotationService.reprocess(id, engine);
    }

    /**
     * Reads the quotation with each chosen engine (all available engines when none is given) and returns the
     * graded readings side by side. Nothing is saved. Waits for the engines, so it can take a minute.
     */
    @PostMapping("/cross-check")
    @PreAuthorize(CAN_EDIT)
    public CrossCheckResponse crossCheck(@PathVariable Long id,
                                         @RequestParam(required = false) List<ExtractionEngine> engines) {
        return crossCheckService.crossCheck(id, engines);
    }

    @PostMapping("/mark-ready")
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse markReady(@PathVariable Long id) {
        return quotationService.markReady(id);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse addItem(@PathVariable Long id, @Valid @RequestBody QuotationItemRequest request) {
        return quotationService.addItem(id, request);
    }

    @PutMapping("/items/{itemId}")
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse updateItem(@PathVariable Long id, @PathVariable Long itemId,
                                        @Valid @RequestBody QuotationItemRequest request) {
        return quotationService.updateItem(id, itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        return quotationService.deleteItem(id, itemId);
    }

    @PostMapping("/flags/{flagId}/resolve")
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse resolveFlag(@PathVariable Long id, @PathVariable Long flagId,
                                         @Valid @RequestBody(required = false) ResolveFlagRequest request) {
        return quotationService.resolveFlag(id, flagId, request == null ? null : request.comment());
    }
}
