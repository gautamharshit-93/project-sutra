package com.sutra.response;

import com.sutra.calculation.ComparisonResult;
import com.sutra.entity.Approval;
import com.sutra.entity.AuditLog;
import com.sutra.entity.ComparisonReport;
import com.sutra.entity.enums.ApprovalDecision;
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
        public static ComparisonReportResponse from(ComparisonReport r, ComparisonResult result, boolean latest) {
            return new ComparisonReportResponse(r.getId(), r.getPurchaseRequest().getId(), r.getReportVersion(),
                    r.getGeneratedBy().getUsername(), r.getGeneratedAt(),
                    r.getLowestCompleteQuotation() == null ? null : r.getLowestCompleteQuotation().getId(),
                    latest, result);
        }
    }

    public record ApprovalResponse(Long id, Long purchaseRequestId, Long comparisonReportId,
                                   Integer comparisonReportVersion, Long selectedQuotationId,
                                   String selectedVendorName, ApprovalDecision decision, String reason,
                                   String decidedBy, Instant decidedAt) {
        public static ApprovalResponse from(Approval a) {
            return new ApprovalResponse(a.getId(), a.getPurchaseRequest().getId(), a.getComparisonReport().getId(),
                    a.getComparisonReport().getReportVersion(),
                    a.getSelectedQuotation() == null ? null : a.getSelectedQuotation().getId(),
                    a.getSelectedQuotation() == null ? null : a.getSelectedQuotation().getVendor().getName(),
                    a.getDecision(), a.getReason(), a.getDecidedBy().getUsername(), a.getDecidedAt());
        }
    }

    public record AuditLogResponse(Long id, String entityType, Long entityId, String action, String performedBy,
                                   Instant performedAt, String details) {
        public static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(a.getId(), a.getEntityType(), a.getEntityId(), a.getAction(),
                    a.getPerformedBy(), a.getPerformedAt(), a.getDetails());
        }
    }

    public record DashboardResponse(long entities, long customers, long vendors,
                                    Map<String, Long> purchaseRequestsByStatus,
                                    Map<String, Long> quotationsByStatus,
                                    String extractionEngine, String extractionModel,
                                    boolean extractionConfigured, boolean aiServiceAvailable) {
    }
}
