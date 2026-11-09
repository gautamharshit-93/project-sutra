package com.sutra.procurement.controller;

import com.sutra.procurement.ai.ExtractionChoice;
import com.sutra.procurement.ai.ExtractionEngine;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.request.PurchaseRequestRequests.NewRfqRequest;
import com.sutra.procurement.request.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.procurement.request.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.procurement.request.PurchaseRequestRequests.RfqTextRequest;
import com.sutra.procurement.request.PurchaseRequestRequests.StatusChangeRequest;
import com.sutra.procurement.request.QuotationRequests.ApprovalRequest;
import com.sutra.procurement.response.DecisionResponses.ApprovalResponse;
import com.sutra.procurement.response.DecisionResponses.ComparisonReportResponse;
import com.sutra.common.web.PageResponse;
import com.sutra.procurement.response.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.procurement.response.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.procurement.response.QuotationResponses.QuotationResponse;
import com.sutra.procurement.response.QuotationResponses.QuotationSummary;
import com.sutra.procurement.response.RfqDraftResponses.RfqDraft;
import com.sutra.procurement.service.ApprovalService;
import com.sutra.procurement.service.ComparisonService;
import com.sutra.procurement.service.PurchaseRequestService;
import com.sutra.procurement.service.QuotationService;
import com.sutra.procurement.service.RfqDraftService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.sutra.common.security.Roles;
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
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/purchase-requests")
public class PurchaseRequestController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN','PROCUREMENT')";

    private final PurchaseRequestService purchaseRequestService;
    private final QuotationService quotationService;
    private final ComparisonService comparisonService;
    private final ApprovalService approvalService;
    private final RfqDraftService rfqDraftService;

    public PurchaseRequestController(PurchaseRequestService purchaseRequestService, QuotationService quotationService,
                                     ComparisonService comparisonService, ApprovalService approvalService,
                                     RfqDraftService rfqDraftService) {
        this.rfqDraftService = rfqDraftService;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse create(@Valid @RequestBody PurchaseRequestRequest request) {
        return purchaseRequestService.create(request);
    }

    /** Header + items (+ open to vendors) in one step, e.g. after the buyer confirms an RFQ read from a document. */
    @PostMapping("/with-items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_EDIT)
    public PurchaseRequestResponse createWithItems(@Valid @RequestBody NewRfqRequest request) {
        return purchaseRequestService.createWithItems(request);
    }

    /** Reads dropped RFQ files into a draft. Nothing is saved. */
    @PostMapping(path = "/draft-from-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CAN_EDIT)
    public RfqDraft draftFromFiles(@RequestPart("files") List<MultipartFile> files) {
        return rfqDraftService.fromFiles(files);
    }

    /** Reads pasted RFQ text into a draft. Nothing is saved. */
    @PostMapping("/draft-from-text")
    @PreAuthorize(CAN_EDIT)
    public RfqDraft draftFromText(@Valid @RequestBody RfqTextRequest request) {
        return rfqDraftService.fromText(request.text());
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
                                    @RequestParam(defaultValue = "AUTO") ExtractionEngine engine,
                                    @RequestParam(required = false) String model) {
        // One quotation may have several files (e.g. the same offer as PDF, Word and Excel); they are read together.
        return quotationService.upload(id, vendorId, files, new ExtractionChoice(engine, model));
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
