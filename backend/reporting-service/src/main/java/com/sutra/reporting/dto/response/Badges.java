package com.sutra.reporting.dto.response;

import java.util.List;

/** The small counts shown next to the menu: what is waiting for the signed-in user right now. */
public final class Badges {

    private Badges() {
    }

    /** For company users; a count is 0 when it is not the user's job. */
    public record StaffBadges(long rfqsToCompare, long awaitingAward) {
    }

    /** For a vendor: open RFQs without its quotation, and its quotations that have an outcome (the UI tracks seen). */
    public record VendorBadges(long openToQuote, List<Long> decidedQuotationIds) {
    }
}
