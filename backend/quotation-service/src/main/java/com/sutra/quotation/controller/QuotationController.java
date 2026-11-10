package com.sutra.quotation.controller;

import com.sutra.quotation.service.QuotationService;
import com.sutra.quotation.dto.response.DownloadedFile;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.enums.ExtractionEngine;
import com.sutra.api.quotation.QuotationRequests.QuotationItemRequest;
import com.sutra.api.quotation.QuotationRequests.QuotationUpdateRequest;
import com.sutra.api.quotation.QuotationRequests.ResolveFlagRequest;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.sutra.common.security.Roles;
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
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/quotations/{id}")
public class QuotationController {

    private static final String CAN_EDIT = Roles.BUYER;

    private final QuotationService quotationService;

    public QuotationController(QuotationService quotationService) {
        this.quotationService = quotationService;
    }

    @GetMapping
    public QuotationResponse get(@PathVariable Long id) {
        return quotationService.get(id);
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long fileId) {
        DownloadedFile file = quotationService.download(id, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(file.sizeBytes())
                .body(file.content());
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
                                       @RequestParam(defaultValue = "AUTO") ExtractionEngine engine,
                                       @RequestParam(required = false) String model) {
        return quotationService.reprocess(id, new ExtractionChoice(engine, model));
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
