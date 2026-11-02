package com.sutra.controller;

import com.sutra.ai.ExtractionEngine;
import com.sutra.entity.enums.PurchaseRequestStatus;
import com.sutra.request.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.request.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.request.PurchaseRequestRequests.StatusChangeRequest;
import com.sutra.request.QuotationRequests.ApprovalRequest;
import com.sutra.response.DecisionResponses.ApprovalResponse;
import com.sutra.response.DecisionResponses.AuditLogResponse;
import com.sutra.response.DecisionResponses.ComparisonReportResponse;
import com.sutra.response.PageResponse;
import com.sutra.response.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.response.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.response.QuotationResponses.QuotationResponse;
import com.sutra.response.QuotationResponses.QuotationSummary;
import com.sutra.service.ApprovalService;
import com.sutra.service.ComparisonService;
import com.sutra.service.PurchaseRequestService;
import com.sutra.service.QuotationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

/** Purchase requests, plus their quotations, comparisons and approvals as sub-resources. */
@RestController
@RequestMapping("/api/purchase-requests")
public class PurchaseRequestController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN','PROCUREMENT')";

    private final PurchaseRequestService purchaseRequestService;
    private final QuotationService quotationService;
    private final ComparisonService comparisonService;
    private final ApprovalService approvalService;

    public PurchaseRequestController(PurchaseRequestService purchaseRequestService, QuotationService quotationService,
                                     ComparisonService comparisonService, ApprovalService approvalService) {
        this.purchaseRequestService = purchaseRequestService;
        this.quotationService = quotationService;
        this.comparisonService = comparisonService;
        this.approvalService = approvalService;
    }

    @GetMapping
    public PageResponse<PurchaseRequestSummary> search(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) PurchaseRequestStatus status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return purchaseRequestService.search(q, status, page, size);
    }

    @GetMapping("/{id}")
    public PurchaseRequestResponse get(@PathVariable Long id) {
        return purchaseRequestService.get(id);
    }

    @GetMapping("/{id}/history")
    public PageResponse<AuditLogResponse> history(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "100") int size) {
        return purchaseRequestService.history(id, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse create(@Valid @RequestBody PurchaseRequestRequest request) {
        return purchaseRequestService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse update(@PathVariable Long id, @Valid @RequestBody PurchaseRequestRequest request) {
        return purchaseRequestService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        purchaseRequestService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest request) {
        return purchaseRequestService.changeStatus(id, request.status());
    }

    // ---------- items ----------

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse addItem(@PathVariable Long id, @Valid @RequestBody RequestItemRequest request) {
        return purchaseRequestService.addItem(id, request);
    }

    @PutMapping("/{id}/items/{itemId}")
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse updateItem(@PathVariable Long id, @PathVariable Long itemId,
                                              @Valid @RequestBody RequestItemRequest request) {
        return purchaseRequestService.updateItem(id, itemId, request);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        return purchaseRequestService.deleteItem(id, itemId);
    }

    // ---------- quotations ----------

    @GetMapping("/{id}/quotations")
    public List<QuotationSummary> quotations(@PathVariable Long id) {
        return quotationService.listForRequest(id);
    }

    @PostMapping(path = "/{id}/quotations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(CAN_EDIT)
    public QuotationResponse upload(@PathVariable Long id, @RequestParam Long vendorId,
                                    @RequestPart("files") List<MultipartFile> files,
                                    @RequestParam(defaultValue = "AUTO") ExtractionEngine engine) {
        // One quotation may have several files (e.g. the same offer as PDF, Word and Excel); they are read together.
        return quotationService.upload(id, vendorId, files, engine);
    }

    // ---------- comparisons ----------

    @GetMapping("/{id}/comparisons")
    public List<ComparisonReportResponse> comparisons(@PathVariable Long id) {
        return comparisonService.list(id);
    }

    @PostMapping("/{id}/comparisons")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_EDIT)
    public ComparisonReportResponse generateComparison(@PathVariable Long id) {
        return comparisonService.generate(id);
    }

    // ---------- approvals ----------

    @GetMapping("/{id}/approvals")
    public List<ApprovalResponse> approvals(@PathVariable Long id) {
        return approvalService.list(id);
    }

    @PostMapping("/{id}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public ApprovalResponse decide(@PathVariable Long id, @Valid @RequestBody ApprovalRequest request) {
        return approvalService.decide(id, request);
    }
}
