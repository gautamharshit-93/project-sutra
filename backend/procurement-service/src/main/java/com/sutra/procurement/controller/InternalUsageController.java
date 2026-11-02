package com.sutra.procurement.controller;

import com.sutra.procurement.repository.PurchaseRequestRepository;
import com.sutra.procurement.repository.QuotationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets master data and auth check, before deleting a record, whether procurement still refers to it
 * (service tokens only; the gateway never forwards {@code /internal}).
 */
@RestController
@RequestMapping("/internal/usage")
@Transactional(readOnly = true)
public class InternalUsageController {

    public record Usage(long purchaseRequests, long quotations) {
    }

    private final PurchaseRequestRepository purchaseRequests;
    private final QuotationRepository quotations;

    public InternalUsageController(PurchaseRequestRepository purchaseRequests, QuotationRepository quotations) {
        this.purchaseRequests = purchaseRequests;
        this.quotations = quotations;
    }

    /** {@code kind}: entities, sub-entities, customers, vendors or users. */
    @GetMapping("/{kind}/{id}")
    public ResponseEntity<Usage> usage(@PathVariable String kind, @PathVariable Long id) {
        Usage usage = switch (kind) {
            case "entities" -> new Usage(purchaseRequests.countByEntityId(id), 0);
            case "sub-entities" -> new Usage(purchaseRequests.countBySubEntityId(id), 0);
            case "customers" -> new Usage(purchaseRequests.countByCustomerId(id), 0);
            case "vendors" -> new Usage(0, quotations.countByVendorId(id));
            case "users" -> new Usage(purchaseRequests.countByRequestedById(id), quotations.countByUploadedById(id));
            default -> null;
        };
        return usage == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(usage);
    }
}
