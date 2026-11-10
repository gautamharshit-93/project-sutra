package com.sutra.quotation.support;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.quotation.config.QuotationProperties;
import com.sutra.quotation.entity.NamedRef;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationItem;
import com.sutra.quotation.entity.UserRef;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builders for quotation unit tests. Entities get ids as if they had been persisted. The RFQ is what the RFQ
 * service returns ({@link TestRfq}); users are signed-in principals; vendors are master-data references.
 */
public final class Fixtures {

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC);

    private static final AtomicLong IDS = new AtomicLong(1000);

    private Fixtures() {
    }

    public static long nextId() {
        return IDS.incrementAndGet();
    }

    public static QuotationProperties properties() {
        return new QuotationProperties(new BigDecimal("0.75"), new BigDecimal("0.6"));
    }

    /** A signed-in company user. */
    public static SutraUserPrincipal user(String username, RoleName... roles) {
        return new SutraUserPrincipal(nextId(), username, null, true, Set.of(roles));
    }

    /** A signed-in vendor login acting for {@code vendor}. */
    public static SutraUserPrincipal vendorUser(String username, NamedRef vendor) {
        return new SutraUserPrincipal(nextId(), username, null, true, Set.of(RoleName.VENDOR), vendor.getId(), 0);
    }

    public static SutraUserPrincipal principal(SutraUserPrincipal user) {
        return user;
    }

    public static NamedRef vendor(String name) {
        return new NamedRef(nextId(), name);
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

    public static Quotation quotation(TestRfq pr, NamedRef vendor) {
        Quotation q = new Quotation();
        q.setId(nextId());
        q.setPurchaseRequestId(pr.getId());
        q.setRequestNumber(pr.getRequestNumber());
        q.setVendor(vendor);
        q.setCurrency("INR");
        q.setStatus(QuotationStatus.NEEDS_REVIEW);
        q.setValidUntil(TODAY.plusDays(30));
        q.setPaymentTerms("30 days");
        q.setDeliveryTerms("2 weeks");
        q.setVersion(0L);
        q.setUploadedBy(new UserRef(pr.getRequestedById(), pr.getRequestedBy()));
        return q;
    }

    /** Adds a priced line matched to {@code matched} (may be null). */
    public static QuotationItem item(Quotation q, TestRfq.Item matched, String description, String qty, String price,
                                     String taxPercent) {
        QuotationItem item = new QuotationItem();
        item.setId(nextId());
        item.setLineNumber(q.nextLineNumber());
        item.setDescription(description);
        item.setQuantity(qty == null ? null : new BigDecimal(qty));
        item.setUnitOfMeasure("Nos");
        item.setUnitPrice(price == null ? null : new BigDecimal(price));
        item.setTaxPercent(taxPercent == null ? null : new BigDecimal(taxPercent));
        item.setRequestItemId(matched == null ? null : matched.getId());
        item.setMatchConfidence(matched == null ? null : new BigDecimal("0.950"));
        q.addItem(item);
        return item;
    }
}
