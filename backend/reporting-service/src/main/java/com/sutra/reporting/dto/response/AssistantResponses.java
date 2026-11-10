package com.sutra.reporting.dto.response;

import com.sutra.api.rfq.RfqDraftResponses.RfqDraft;
import java.util.List;

public final class AssistantResponses {

    private AssistantResponses() {
    }

    /** Whether the assistant can answer (it needs Groq) and which model it uses. */
    public record AssistantStatus(boolean enabled, String model, String reason) {
    }

    /** A record the answer refers to; the UI turns it into a link. {@code type} is PURCHASE_REQUEST or QUOTATION. */
    public record AssistantLink(String type, Long id, String label) {
    }

    /** {@code rfqDraft} is set when the user pasted an RFQ: the UI shows it as a card to check and confirm. */
    public record ChatResponse(String reply, List<AssistantLink> links, RfqDraft rfqDraft) {
        public ChatResponse(String reply, List<AssistantLink> links) {
            this(reply, links, null);
        }
    }
}
