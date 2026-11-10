package com.sutra.comparison.controller;

import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.common.security.Roles;
import com.sutra.comparison.service.ComparisonService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** An RFQ's comparison reports (the gateway routes these RFQ paths here), and one report by id. */
@RestController
@RequestMapping("/api")
@PreAuthorize(Roles.STAFF)
public class ComparisonController {

    private final ComparisonService comparisons;

    public ComparisonController(ComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    @GetMapping("/purchase-requests/{id}/comparisons")
    public List<ComparisonReportResponse> list(@PathVariable Long id) {
        return comparisons.list(id);
    }

    @PostMapping("/purchase-requests/{id}/comparisons")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.BUYER)
    public ComparisonReportResponse generate(@PathVariable Long id) {
        return comparisons.generate(id);
    }

    @GetMapping("/comparisons/{reportId}")
    public ComparisonReportResponse get(@PathVariable Long reportId) {
        return comparisons.get(reportId);
    }
}
