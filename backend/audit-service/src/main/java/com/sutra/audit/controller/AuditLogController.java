package com.sutra.audit.controller;

import com.sutra.audit.service.AuditLogService;
import com.sutra.audit.dto.response.AuditLogResponse;
import com.sutra.audit.dto.response.ChainVerificationResponse;
import com.sutra.common.security.Roles;
import com.sutra.common.web.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail. The full log is for administrators, managers and auditors; the history of one RFQ or quotation
 * is visible to every company user. Vendors never see the audit trail.
 */
@RestController
@RequestMapping("/api")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/audit-logs")
    @PreAuthorize(Roles.AUDIT_READERS)
    public PageResponse<AuditLogResponse> search(@RequestParam(required = false) String entityType,
                                                 @RequestParam(required = false) Long entityId,
                                                 @RequestParam(required = false) String performedBy,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return auditLogService.search(entityType, entityId, performedBy, page, size);
    }

    /** Everything that happened to an RFQ, its quotations, comparisons and award. */
    @GetMapping("/purchase-requests/{id}/history")
    @PreAuthorize(Roles.STAFF)
    public PageResponse<AuditLogResponse> purchaseRequestHistory(@PathVariable Long id,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "100") int size) {
        return auditLogService.purchaseRequestHistory(id, page, size);
    }

    /** Recomputes the SHA-256 hash chain: proves no audit entry was changed, removed or re-ordered. */
    @GetMapping("/audit-logs/verify")
    @PreAuthorize(Roles.AUDIT_READERS)
    public ChainVerificationResponse verify() {
        return auditLogService.verifyChain();
    }

    @GetMapping("/quotations/{id}/history")
    @PreAuthorize(Roles.STAFF)
    public PageResponse<AuditLogResponse> quotationHistory(@PathVariable Long id,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "100") int size) {
        return auditLogService.search("QUOTATION", id, null, page, size);
    }
}
