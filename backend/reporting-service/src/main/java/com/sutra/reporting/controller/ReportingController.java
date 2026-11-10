package com.sutra.reporting.controller;

import com.sutra.api.decision.DecisionResponses.DashboardResponse;
import com.sutra.common.security.Roles;
import com.sutra.reporting.dto.response.AwardSummary;
import com.sutra.reporting.dto.response.Badges.StaffBadges;
import com.sutra.reporting.dto.response.Badges.VendorBadges;
import com.sutra.reporting.service.AwardSummaryService;
import com.sutra.reporting.service.BadgeService;
import com.sutra.reporting.service.DashboardService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard, menu badges and the award summary PDF (the gateway routes these paths here). */
@RestController
@RequestMapping("/api")
public class ReportingController {

    private final DashboardService dashboard;
    private final BadgeService badges;
    private final AwardSummaryService awardSummaries;

    public ReportingController(DashboardService dashboard, BadgeService badges, AwardSummaryService awardSummaries) {
        this.dashboard = dashboard;
        this.badges = badges;
        this.awardSummaries = awardSummaries;
    }

    @GetMapping("/dashboard")
    @PreAuthorize(Roles.STAFF)
    public DashboardResponse dashboard() {
        return dashboard.summary();
    }

    @GetMapping("/dashboard/badges")
    @PreAuthorize(Roles.STAFF)
    public StaffBadges staffBadges() {
        return badges.staff();
    }

    /** Menu badges of the vendor portal: open RFQs not quoted on yet, and quotations that have an outcome. */
    @GetMapping("/vendor-portal/badges")
    @PreAuthorize(Roles.VENDOR)
    public VendorBadges vendorBadges() {
        return badges.vendor();
    }

    @GetMapping("/purchase-requests/{id}/award-summary")
    @PreAuthorize(Roles.STAFF)
    public ResponseEntity<byte[]> awardSummary(@PathVariable Long id) {
        AwardSummary summary = awardSummaries.summary(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(summary.fileName()).build().toString())
                .body(summary.pdf());
    }
}
