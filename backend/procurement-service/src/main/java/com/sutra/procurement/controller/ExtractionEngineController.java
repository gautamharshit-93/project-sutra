package com.sutra.procurement.controller;

import com.sutra.procurement.response.CrossCheckResponses.EnginesResponse;
import com.sutra.procurement.service.ExtractionCrossCheckService;
import com.sutra.common.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The extraction engines a user can choose from when uploading, retrying or cross-checking a quotation. */
@RestController
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/extraction-engines")
public class ExtractionEngineController {

    private final ExtractionCrossCheckService crossCheckService;

    public ExtractionEngineController(ExtractionCrossCheckService crossCheckService) {
        this.crossCheckService = crossCheckService;
    }

    @GetMapping
    public EnginesResponse engines() {
        return crossCheckService.engines();
    }
}
