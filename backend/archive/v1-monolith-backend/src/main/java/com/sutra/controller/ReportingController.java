package com.sutra.controller;

import com.sutra.response.DecisionResponses.AuditLogResponse;
import com.sutra.response.DecisionResponses.ComparisonReportResponse;
import com.sutra.response.DecisionResponses.DashboardResponse;
import com.sutra.response.PageResponse;
import com.sutra.service.AuditService;
import com.sutra.service.ComparisonService;
import com.sutra.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard, stored comparison reports and the audit trail. */
@RestController
@RequestMapping("/api")
public class ReportingController {

    private final DashboardService dashboardService;
    private final ComparisonService comparisonService;
    private final AuditService auditService;

    public ReportingController(DashboardService dashboardService, ComparisonService comparisonService,
                               AuditService auditService) {
        this.dashboardService = dashboardService;
        this.comparisonService = comparisonService;
        this.auditService = auditService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return dashboardService.summary();
    }

    @GetMapping("/comparisons/{reportId}")
    public ComparisonReportResponse comparison(@PathVariable Long reportId) {
        return comparisonService.get(reportId);
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasAnyRole('ADMIN','APPROVER')")
    public PageResponse<AuditLogResponse> auditLogs(@RequestParam(required = false) String entityType,
                                                    @RequestParam(required = false) Long entityId,
                                                    @RequestParam(required = false) String performedBy,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return auditService.search(entityType, entityId, performedBy, page, size);
    }
}
