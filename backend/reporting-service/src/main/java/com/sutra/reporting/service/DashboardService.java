package com.sutra.reporting.service;

import com.sutra.api.decision.DecisionResponses.DashboardResponse;

/** Counts for the dashboard, gathered from the services that own them. */
public interface DashboardService {

    DashboardResponse summary();
}
