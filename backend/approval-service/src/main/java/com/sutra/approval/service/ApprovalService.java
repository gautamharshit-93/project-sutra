package com.sutra.approval.service;

import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.quotation.QuotationRequests.ApprovalRequest;

import java.util.Collection;
import java.util.List;

/** Award or reject decisions on compared RFQs. Only ever recorded by a manager or an administrator. */
public interface ApprovalService {

    List<ApprovalResponse> list(Long purchaseRequestId);

    /**
     * Records the decision on the latest, still current comparison: a reason is required when the award is not the
     * lowest complete quotation (or it has expired); a manager cannot decide on an RFQ they raised.
     */
    ApprovalResponse decide(Long purchaseRequestId, ApprovalRequest request);

    /** The newest decision of each RFQ that has one. */
    List<ApprovalResponse> latestFor(Collection<Long> rfqIds);

    long decisionsBy(Long userId);
}
