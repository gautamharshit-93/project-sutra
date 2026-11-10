package com.sutra.reporting.controller;

import com.sutra.common.security.Roles;
import com.sutra.reporting.dto.request.AssistantRequests.ChatRequest;
import com.sutra.reporting.dto.response.AssistantResponses.AssistantStatus;
import com.sutra.reporting.dto.response.AssistantResponses.ChatResponse;
import com.sutra.reporting.service.AssistantService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The read-only Hindi/English assistant. It answers questions about quotations; it never changes data. */
@RestController
@PreAuthorize(Roles.STAFF)
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @GetMapping("/status")
    public AssistantStatus status() {
        return assistantService.status();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return assistantService.chat(request);
    }
}
