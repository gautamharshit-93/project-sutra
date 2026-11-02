package com.sutra.controller;

import com.sutra.response.CrossCheckResponses.EnginesResponse;
import com.sutra.service.ExtractionCrossCheckService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The extraction engines a user can choose from when uploading, retrying or cross-checking a quotation. */
@RestController
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
