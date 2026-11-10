package com.sutra.reporting.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AssistantRequests {

    private AssistantRequests() {
    }

    /** One earlier turn of the conversation, sent back by the browser (the server keeps no chat history). */
    public record ChatTurn(@NotBlank @Pattern(regexp = "user|assistant") String role,
                           @NotBlank @Size(max = 20000) String text) {
    }

    /**
     * A question for the assistant. {@code purchaseRequestId} / {@code quotationId} name the record the user is
     * looking at, so "is quotation ko check karo" can be answered without repeating the number.
     */
    public record ChatRequest(@NotBlank @Size(max = 20000) String message,
                              @Size(max = 20) List<@Valid ChatTurn> history,
                              Long purchaseRequestId,
                              Long quotationId) {
    }
}
