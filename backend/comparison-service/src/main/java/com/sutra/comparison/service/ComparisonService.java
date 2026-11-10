package com.sutra.comparison.service;

import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;

import java.util.List;

/** Versioned side-by-side comparisons of an RFQ's quotations that are ready for comparison. */
public interface ComparisonService {

    /** Compares the RFQ's ready quotations, saves the next version and moves an OPEN RFQ to UNDER_REVIEW. */
    ComparisonReportResponse generate(Long purchaseRequestId);

    List<ComparisonReportResponse> list(Long purchaseRequestId);

    ComparisonReportResponse get(Long reportId);

    /** The newest report of the RFQ, if any. */
    java.util.Optional<ComparisonReportResponse> latest(Long purchaseRequestId);

    /** True when any compared quotation changed after the report was generated. */
    boolean isStale(Long reportId);

    /** How many reports include the quotation. */
    long references(Long quotationId);
}
