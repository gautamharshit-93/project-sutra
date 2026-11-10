package com.sutra.reporting.service;

import com.sutra.reporting.dto.request.AssistantRequests.ChatRequest;
import com.sutra.reporting.dto.response.AssistantResponses.AssistantStatus;
import com.sutra.reporting.dto.response.AssistantResponses.ChatResponse;

/**
 * Sahayak, the read-only assistant: answers questions about RFQs, quotations and comparisons from the services'
 * data (the AI reads; it never changes anything), and turns an RFQ pasted into the chat into a draft card.
 */
public interface AssistantService {

    AssistantStatus status();

    ChatResponse chat(ChatRequest request);
}
