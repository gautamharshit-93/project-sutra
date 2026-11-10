package com.sutra.rfq.controller;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.rfq.PurchaseRequestRequests.NewRfqRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.StatusChangeRequest;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.common.security.Roles;
import com.sutra.common.web.PageResponse;
import com.sutra.rfq.service.PurchaseRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFQs and their items. (Quotations, comparisons, approvals, drafts and the award summary of an RFQ are served by
 * their own services under the same {@code /api/purchase-requests/{id}/...} paths; the gateway routes them.)
 */
@RestController
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/purchase-requests")
public class PurchaseRequestController {

    private final PurchaseRequestService purchaseRequestService;

    public PurchaseRequestController(PurchaseRequestService purchaseRequestService) {
        this.purchaseRequestService = purchaseRequestService;
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
    @PreAuthorize(Roles.BUYER)
    public PurchaseRequestResponse create(@Valid @RequestBody PurchaseRequestRequest request) {
        return purchaseRequestService.create(request);
    }

    @PostMapping("/with-items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.BUYER)
    public PurchaseRequestResponse createWithItems(@Valid @RequestBody NewRfqRequest request) {
        return purchaseRequestService.createWithItems(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.BUYER)
    public PurchaseRequestResponse update(@PathVariable Long id, @Valid @RequestBody PurchaseRequestRequest request) {
        return purchaseRequestService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.BUYER)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        purchaseRequestService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(Roles.BUYER)
    public PurchaseRequestResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest request) {
        return purchaseRequestService.changeStatus(id, request.status());
    }

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.BUYER)
    public PurchaseRequestResponse addItem(@PathVariable Long id, @Valid @RequestBody RequestItemRequest request) {
        return purchaseRequestService.addItem(id, request);
    }

    @PutMapping("/{id}/items/{itemId}")
    @PreAuthorize(Roles.BUYER)
    public PurchaseRequestResponse updateItem(@PathVariable Long id, @PathVariable Long itemId,
                                              @Valid @RequestBody RequestItemRequest request) {
        return purchaseRequestService.updateItem(id, itemId, request);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize(Roles.BUYER)
    public PurchaseRequestResponse deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        return purchaseRequestService.deleteItem(id, itemId);
    }
}
