package com.sutra.procurement.support;

import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.procurement.config.SutraProperties;
import com.sutra.procurement.entity.NamedRef;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.RequestItem;
import com.sutra.procurement.entity.UserRef;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builders for procurement unit tests. Entities get ids as if they had been persisted. Users are the signed-in
 * principals procurement sees (it has no user table); vendors and entities are master-data references.
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

    public static SutraProperties properties() {
        return new SutraProperties(
                new SutraProperties.Files(1024 * 1024),
                new SutraProperties.Ai("http://localhost:8000", 5,
                        new SutraProperties.Gemini("", "gemini-3.8-flash", "http://gemini.test", 16000, 30),
                        new BigDecimal("0.75"), new BigDecimal("0.6")));
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

    public static SutraUserPrincipal principal(Long id, RoleName... roles) {
        return new SutraUserPrincipal(id, "user" + id, null, true, Set.of(roles));
    }

    public static NamedRef entity() {
        return new NamedRef(nextId(), "Head Office");
    }

    public static NamedRef vendor(String name) {
        return new NamedRef(nextId(), name);
    }

    /** An OPEN request in INR for 10 laptops and 10 monitors. */
    public static PurchaseRequest purchaseRequest(SutraUserPrincipal owner) {
        PurchaseRequest pr = new PurchaseRequest();
        pr.setId(nextId());
        pr.setRequestNumber("PR-2026-00001");
        pr.setTitle("Laptops for new joiners");
        pr.setEntity(entity());
        pr.setRequestedBy(UserRef.of(owner));
        pr.setStatus(PurchaseRequestStatus.OPEN);
        pr.setCurrency("INR");
        pr.addItem(requestItem(1, "Laptop Dell Latitude 5440", "10"));
        pr.addItem(requestItem(2, "24 inch LED Monitor", "10"));
        return pr;
    }

    public static RequestItem requestItem(int line, String description, String quantity) {
        RequestItem item = new RequestItem();
        item.setId(nextId());
        item.setLineNumber(line);
        item.setDescription(description);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitOfMeasure("Nos");
        return item;
    }

    public static Quotation quotation(PurchaseRequest pr, NamedRef vendor) {
        Quotation q = new Quotation();
        q.setId(nextId());
        q.setPurchaseRequest(pr);
        q.setVendor(vendor);
        q.setCurrency("INR");
        q.setStatus(QuotationStatus.NEEDS_REVIEW);
        q.setValidUntil(TODAY.plusDays(30));
        q.setPaymentTerms("30 days");
        q.setDeliveryTerms("2 weeks");
        q.setVersion(0L);
        q.setUploadedBy(pr.getRequestedBy());
        return q;
    }

    /** Adds a priced line matched to {@code matched} (may be null). */
    public static QuotationItem item(Quotation q, RequestItem matched, String description, String qty, String price,
                                     String taxPercent) {
        QuotationItem item = new QuotationItem();
        item.setId(nextId());
        item.setLineNumber(q.nextLineNumber());
        item.setDescription(description);
        item.setQuantity(qty == null ? null : new BigDecimal(qty));
        item.setUnitOfMeasure("Nos");
        item.setUnitPrice(price == null ? null : new BigDecimal(price));
        item.setTaxPercent(taxPercent == null ? null : new BigDecimal(taxPercent));
        item.setRequestItem(matched);
        item.setMatchConfidence(matched == null ? null : new BigDecimal("0.950"));
        q.addItem(item);
        return item;
    }
}
