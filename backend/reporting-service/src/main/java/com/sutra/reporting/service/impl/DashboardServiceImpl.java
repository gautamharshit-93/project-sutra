package com.sutra.reporting.service.impl;

import com.sutra.api.decision.DecisionResponses.DashboardResponse;
import com.sutra.api.internal.InternalContracts.AiStatus;
import com.sutra.common.error.ServiceUnavailableException;
import com.sutra.reporting.client.MasterDataClient;
import com.sutra.reporting.client.PlatformClient;
import com.sutra.reporting.service.DashboardService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Counts for the dashboard. A service that is down leaves its part empty instead of failing the whole page:
 * master-data counts are null, and the reading status says the document reader is unavailable.
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private final MasterDataClient masterData;
    private final PlatformClient platform;

    public DashboardServiceImpl(MasterDataClient masterData, PlatformClient platform) {
        this.masterData = masterData;
        this.platform = platform;
    }

    @Override
    public DashboardResponse summary() {
        MasterDataClient.Stats stats;
        try {
            stats = masterData.stats();
        } catch (ServiceUnavailableException e) {
            stats = null;
        }
        Map<String, Long> rfqs = platform.rfqCounts();
        Map<String, Long> quotations = platform.quotationCounts();
        AiStatus ai;
        try {
            ai = platform.aiStatus();
        } catch (ServiceUnavailableException e) {
            ai = new AiStatus(false, null, "NONE", "none", false, false);
        }
        return new DashboardResponse(stats == null ? null : stats.entities(), stats == null ? null : stats.customers(),
                stats == null ? null : stats.vendors(), rfqs, quotations, ai.extractionEngine(), ai.extractionModel(),
                ai.extractionConfigured(), ai.documentReaderAvailable());
    }
}
