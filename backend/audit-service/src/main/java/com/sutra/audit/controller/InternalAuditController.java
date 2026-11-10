package com.sutra.audit.controller;

import com.sutra.audit.dto.response.IngestResult;
import com.sutra.audit.service.AuditLogService;
import com.sutra.common.audit.AuditEventMessage;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives audit events from the other services' outboxes (service tokens only). */
@RestController
@RequestMapping("/internal/audit-events")
public class InternalAuditController {

    private final AuditLogService auditLogService;

    public InternalAuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @PostMapping
    public IngestResult ingest(@RequestBody List<AuditEventMessage> events) {
        return new IngestResult(events.size(), auditLogService.ingest(events));
    }
}
