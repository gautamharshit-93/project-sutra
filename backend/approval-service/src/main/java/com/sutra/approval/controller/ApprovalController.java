package com.sutra.approval.controller;

import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.quotation.QuotationRequests.ApprovalRequest;
import com.sutra.approval.service.ApprovalService;
import com.sutra.common.security.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** An RFQ's award decisions (the gateway routes these RFQ paths here). */
@RestController
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/purchase-requests/{id}/approvals")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ApprovalResponse> list(@PathVariable Long id) {
        return approvals.list(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public ApprovalResponse decide(@PathVariable Long id, @Valid @RequestBody ApprovalRequest request) {
        return approvals.decide(id, request);
    }
}
