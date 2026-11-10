package com.sutra.comparison.support;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Builders for comparison unit tests: RFQs and quotations as their services return them. */
public final class Fixtures {

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC);

    private static final AtomicLong IDS = new AtomicLong(1000);

    private Fixtures() {
    }

    public static long nextId() {
        return IDS.incrementAndGet();
    }

    public static SutraUserPrincipal user(String username, RoleName... roles) {
        return new SutraUserPrincipal(nextId(), username, null, true, Set.of(roles));
    }

    /** An OPEN request in INR for 10 laptops and 10 monitors, raised by {@code owner}. */
    public static TestRfq purchaseRequest(SutraUserPrincipal owner) {
        TestRfq pr = new TestRfq();
        pr.setId(nextId());
        pr.setRequestedById(owner.getId());
        pr.setRequestedBy(owner.getUsername());
        pr.setStatus(PurchaseRequestStatus.OPEN);
        pr.getItems().add(requestItem(1, "Laptop Dell Latitude 5440", "10"));
        pr.getItems().add(requestItem(2, "24 inch LED Monitor", "10"));
        return pr;
    }

    public static TestRfq.Item requestItem(int line, String description, String quantity) {
        TestRfq.Item item = new TestRfq.Item();
        item.setId(nextId());
        item.setLineNumber(line);
        item.setDescription(description);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }

    /** A read quotation, ready for comparison, valid for 30 days. */
    public static TestQuotation quotation(TestRfq pr, String vendor) {
        TestQuotation q = new TestQuotation();
        q.setId(nextId());
        q.setPurchaseRequestId(pr.getId());
        q.setVendorId(nextId());
        q.setVendorName(vendor);
        q.setValidUntil(TODAY.plusDays(30));
        return q;
    }

    /** Adds a priced line matched to {@code matched} (may be null). */
    public static void item(TestQuotation q, TestRfq.Item matched, String description, String qty, String price,
                            String taxPercent) {
        q.getLines().add(new TestQuotation.Line(nextId(), q.getLines().size() + 1,
                matched == null ? null : matched.getId(), matched == null ? null : matched.getLineNumber(), description,
                qty == null ? null : new BigDecimal(qty), price == null ? null : new BigDecimal(price),
                taxPercent == null ? null : new BigDecimal(taxPercent)));
    }

    public static List<QuotationResponse> responses(TestQuotation... quotations) {
        return Arrays.stream(quotations).map(TestQuotation::toResponse).toList();
    }
}
