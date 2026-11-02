package com.sutra.procurement.controller;

import com.sutra.procurement.response.DecisionResponses.ComparisonReportResponse;
import com.sutra.procurement.response.DecisionResponses.DashboardResponse;
import com.sutra.procurement.service.ComparisonService;
import com.sutra.procurement.service.DashboardService;
import com.sutra.common.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard and stored comparison reports. The audit trail is served by the audit service. */
@RestController
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api")
public class ReportingController {

    private final DashboardService dashboardService;
    private final ComparisonService comparisonService;

    public ReportingController(DashboardService dashboardService, ComparisonService comparisonService) {
        this.dashboardService = dashboardService;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return dashboardService.summary();
    }

    @GetMapping("/comparisons/{reportId}")
    public ComparisonReportResponse comparison(@PathVariable Long reportId) {
        return comparisonService.get(reportId);
    }
}
