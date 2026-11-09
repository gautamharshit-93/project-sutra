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
    private final com.sutra.procurement.service.BadgeService badges;

    public ReportingController(DashboardService dashboardService, ComparisonService comparisonService,
                               com.sutra.procurement.service.BadgeService badges) {
        this.dashboardService = dashboardService;
        this.comparisonService = comparisonService;
        this.badges = badges;
    }

    /** Counts for the menu badges: RFQs ready to compare (buyers), RFQs waiting for an award (managers). */
    @GetMapping("/dashboard/badges")
    public com.sutra.procurement.service.BadgeService.StaffBadges badges() {
        return badges.staff();
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
