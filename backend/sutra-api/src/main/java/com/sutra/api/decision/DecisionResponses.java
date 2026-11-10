package com.sutra.api.decision;

import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.enums.ApprovalDecision;
import java.time.Instant;
import java.util.Map;

/** Comparison reports, approvals, audit entries and the dashboard. */
public final class DecisionResponses {

    private DecisionResponses() {
    }

    public record ComparisonReportResponse(Long id, Long purchaseRequestId, Integer reportVersion,
                                           String generatedBy, Instant generatedAt,
                                           Long lowestCompleteQuotationId, boolean latest,
                                           ComparisonResult result) {
    }

    public record ApprovalResponse(Long id, Long purchaseRequestId, Long comparisonReportId,
                                   Integer comparisonReportVersion, Long selectedQuotationId,
                                   String selectedVendorName, ApprovalDecision decision, String reason,
                                   String decidedBy, Instant decidedAt) {
    }

    public record DashboardResponse(Long entities, Long customers, Long vendors,
                                    Map<String, Long> purchaseRequestsByStatus,
                                    Map<String, Long> quotationsByStatus,
                                    String extractionEngine, String extractionModel,
                                    boolean extractionConfigured, boolean aiServiceAvailable) {
    }
}
