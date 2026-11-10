package com.sutra.approval.controller;

import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.internal.InternalContracts.Usage;
import com.sutra.approval.service.ApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Decisions for the quotation and reporting services (service tokens only). */
@RestController
@RequestMapping("/internal")
public class InternalApprovalController {

    private final ApprovalService approvals;

    public InternalApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    /** The newest decision of each RFQ that has one (vendor outcomes, award summary). */
    @GetMapping("/approvals/latest")
    public List<ApprovalResponse> latest(@RequestParam List<Long> rfqIds) {
        return approvals.latestFor(rfqIds);
    }

    @GetMapping("/rfqs/{rfqId}/approvals")
    public List<ApprovalResponse> forRfq(@PathVariable Long rfqId) {
        return approvals.latestFor(List.of(rfqId));
    }

    @GetMapping("/usage/{kind}/{id}")
    public ResponseEntity<Usage> usage(@PathVariable String kind, @PathVariable Long id) {
        return "users".equals(kind) ? ResponseEntity.ok(new Usage(approvals.decisionsBy(id), 0))
                : ResponseEntity.notFound().build();
    }
}
