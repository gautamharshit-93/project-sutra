package com.sutra.rfq.controller;

import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.internal.InternalContracts.StatusChange;
import com.sutra.api.internal.InternalContracts.StatusCounts;
import com.sutra.api.internal.InternalContracts.Usage;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.web.PageResponse;
import com.sutra.rfq.service.PurchaseRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** RFQ data and status changes for the other services (service tokens only). */
@RestController
@RequestMapping("/internal")
public class InternalRfqController {

    private final PurchaseRequestService rfqs;

    public InternalRfqController(PurchaseRequestService rfqs) {
        this.rfqs = rfqs;
    }

    @GetMapping("/rfqs/{id}")
    public PurchaseRequestResponse get(@PathVariable Long id) {
        return rfqs.get(id); // mapped inside the service's transaction (the items load lazily)
    }

    @GetMapping("/rfqs")
    public List<PurchaseRequestResponse> getMany(@RequestParam List<Long> ids) {
        return rfqs.getMany(ids);
    }

    /** Every open RFQ plus the ones the vendor quoted on ({@code quotedIds}), newest first. */
    @GetMapping("/rfqs/vendor-view")
    public PageResponse<PurchaseRequestResponse> vendorView(@RequestParam(required = false) List<Long> quotedIds,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return rfqs.vendorView(quotedIds, page, size);
    }

    @GetMapping("/rfqs/open-ids")
    public List<Long> openIds(@RequestParam(required = false) Long requestedById) {
        return rfqs.openIds(requestedById);
    }

    @GetMapping("/rfqs/awaiting-award-count")
    public long awaitingAward(@RequestParam(required = false) Long excludeRequestedById) {
        return rfqs.countAwaitingAward(excludeRequestedById);
    }

    @GetMapping("/rfqs/stats")
    public StatusCounts stats() {
        return new StatusCounts(rfqs.countByStatus());
    }

    @PostMapping("/rfqs/{id}/status")
    public PurchaseRequestResponse changeStatus(@PathVariable Long id, @RequestBody StatusChange change) {
        return rfqs.applyStatusChange(id, change);
    }

    /** {@code kind}: entities, sub-entities, customers or users. */
    @GetMapping("/usage/{kind}/{id}")
    public ResponseEntity<Usage> usage(@PathVariable String kind, @PathVariable Long id) {
        long count = rfqs.usage(kind, id);
        return count < 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(new Usage(count, 0));
    }

    /** RFQ search as the RFQ list shows it (reporting: dashboard and assistant). */
    @GetMapping("/rfqs/search")
    public PageResponse<PurchaseRequestSummary> search(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) PurchaseRequestStatus status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return rfqs.search(q, status, page, size);
    }
}
