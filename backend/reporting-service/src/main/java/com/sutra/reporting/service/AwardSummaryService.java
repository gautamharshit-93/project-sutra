package com.sutra.reporting.service;

import com.sutra.reporting.dto.response.AwardSummary;

/** The award summary PDF of a decided RFQ. */
public interface AwardSummaryService {

    AwardSummary summary(Long purchaseRequestId);
}
