package com.sutra.procurement.controller;

import com.sutra.common.security.Roles;
import com.sutra.procurement.ai.ExtractionEngines;
import com.sutra.procurement.response.ExtractionEngineResponses.EnginesResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which extraction engines are available (shown on the dashboard). Quotations are always read automatically. */
@RestController
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/extraction-engines")
public class ExtractionEngineController {

    private final ExtractionEngines engines;

    public ExtractionEngineController(ExtractionEngines engines) {
        this.engines = engines;
    }

    @GetMapping
    public EnginesResponse engines() {
        return new EnginesResponse(engines.describe(), engines.autoOrder());
    }
}
