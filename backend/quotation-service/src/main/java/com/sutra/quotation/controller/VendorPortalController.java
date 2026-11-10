package com.sutra.quotation.controller;

import com.sutra.quotation.service.VendorPortalService;
import com.sutra.quotation.dto.response.DownloadedFile;
import com.sutra.common.security.Roles;
import com.sutra.common.web.PageResponse;
import com.sutra.api.quotation.VendorPortalResponses.RfqDetail;
import com.sutra.api.quotation.VendorPortalResponses.RfqSummary;
import com.sutra.api.quotation.VendorPortalResponses.VendorQuotation;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The vendor portal API. Only VENDOR logins; every answer is limited to the caller's own vendor. */
@RestController
@RequestMapping("/api/vendor-portal")
@PreAuthorize(Roles.VENDOR)
public class VendorPortalController {

    private final VendorPortalService portal;

    public VendorPortalController(VendorPortalService portal) {
        this.portal = portal;
    }

    /** RFQs open for quotations, plus those this vendor quoted on. */
    @GetMapping("/rfqs")
    public PageResponse<RfqSummary> rfqs(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return portal.rfqs(page, size);
    }

    @GetMapping("/rfqs/{id}")
    public RfqDetail rfq(@PathVariable Long id) {
        return portal.rfq(id);
    }

    /** Submits this vendor's quotation: one or more PDF, Excel or Word files read together. */
    @PostMapping(path = "/rfqs/{id}/quotations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VendorQuotation submit(@PathVariable Long id, @RequestPart("files") List<MultipartFile> files) {
        return portal.submit(id, files);
    }

    @GetMapping("/quotations")
    public List<VendorQuotation> myQuotations() {
        return portal.myQuotations();
    }

    @DeleteMapping("/quotations/{id}")
    public ResponseEntity<Void> withdraw(@PathVariable Long id) {
        portal.withdraw(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/quotations/{id}/files/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long fileId) {
        DownloadedFile file = portal.download(id, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(file.sizeBytes())
                .body(file.content());
    }
}
