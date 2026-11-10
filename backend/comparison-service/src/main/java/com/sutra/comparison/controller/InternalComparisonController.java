package com.sutra.comparison.controller;

import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.comparison.service.ComparisonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Comparison reports for the approval and quotation services (service tokens only). */
@RestController
@RequestMapping("/internal")
public class InternalComparisonController {

    private final ComparisonService comparisons;

    public InternalComparisonController(ComparisonService comparisons) {
        this.comparisons = comparisons;
    }

    @GetMapping("/comparisons/{reportId}")
    public ComparisonReportResponse get(@PathVariable Long reportId) {
        return comparisons.get(reportId);
    }

    @GetMapping("/rfqs/{rfqId}/comparisons/latest")
    public ResponseEntity<ComparisonReportResponse> latest(@PathVariable Long rfqId) {
        return comparisons.latest(rfqId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** True when a compared quotation changed after the report was generated. */
    @GetMapping("/comparisons/{reportId}/stale")
    public boolean stale(@PathVariable Long reportId) {
        return comparisons.isStale(reportId);
    }

    @GetMapping("/quotations/{quotationId}/comparison-count")
    public long references(@PathVariable Long quotationId) {
        return comparisons.references(quotationId);
    }

    /** Every version of the RFQ's comparison, newest first. */
    @GetMapping("/rfqs/{rfqId}/comparisons")
    public java.util.List<ComparisonReportResponse> list(@PathVariable Long rfqId) {
        return comparisons.list(rfqId);
    }
}
