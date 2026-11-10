package com.sutra.api.rfq;

import java.math.BigDecimal;
import java.util.List;

/** An RFQ read from a document or pasted text by Groq. Nothing is saved until the buyer confirms it. */
public final class RfqDraftResponses {

    private RfqDraftResponses() {
    }

    /**
     * {@code requiredBy} is YYYY-MM-DD or null; {@code buyerName}/{@code customerName} are names as written, which the
     * UI matches against the entities and customers it knows. {@code source} names the files, or "pasted text".
     */
    public record RfqDraft(String title, String description, String requiredBy, String currency, String buyerName,
                           String customerName, List<RfqDraftItem> items, List<String> warnings, String source,
                           String quotationsDue) {
    }

    /** {@code quantity} is null when the RFQ did not state one; the buyer must fill it before saving. */
    public record RfqDraftItem(String itemCode, String description, BigDecimal quantity, String unitOfMeasure,
                               String specifications) {
    }
}
