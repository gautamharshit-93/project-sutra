package com.sutra.support;

import com.sutra.config.SutraProperties;
import com.sutra.entity.AppUser;
import com.sutra.entity.BusinessEntity;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.Quotation;
import com.sutra.entity.QuotationItem;
import com.sutra.entity.RequestItem;
import com.sutra.entity.Role;
import com.sutra.entity.Vendor;
import com.sutra.entity.enums.PurchaseRequestStatus;
import com.sutra.entity.enums.QuotationStatus;
import com.sutra.entity.enums.RoleName;
import com.sutra.security.SutraUserPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Builders for unit tests. Entities get ids as if they had been persisted. */
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
                new SutraProperties.Security("test-secret-test-secret-test-secret-123", 60, List.of("http://localhost"), 5, 15),
                new SutraProperties.BootstrapAdmin("admin", "Admin@123", "admin@test"),
                new SutraProperties.Files(1024 * 1024),
                new SutraProperties.Ai("http://localhost:8000", 5,
                        new SutraProperties.Gemini("", "gemini-3.8-flash", "http://gemini.test", 16000, 30),
                        new BigDecimal("0.75"), new BigDecimal("0.6")));
    }

    public static AppUser user(String username, RoleName... roles) {
        AppUser user = new AppUser();
        user.setId(nextId());
        user.setUsername(username);
        user.setEmail(username + "@test");
        user.setFullName(username);
        user.setPasswordHash("hash");
        for (RoleName role : roles) {
            Role r = new Role(role);
            r.setId((long) role.ordinal() + 1);
            user.getRoles().add(r);
        }
        return user;
    }

    public static SutraUserPrincipal principal(AppUser user) {
        return SutraUserPrincipal.from(user);
    }

    public static SutraUserPrincipal principal(Long id, RoleName... roles) {
        return new SutraUserPrincipal(id, "user" + id, "hash", true, Set.of(roles));
    }

    public static BusinessEntity entity() {
        BusinessEntity e = new BusinessEntity();
        e.setId(nextId());
        e.setCode("HQ");
        e.setName("Head Office");
        return e;
    }

    public static Vendor vendor(String name) {
        Vendor v = new Vendor();
        v.setId(nextId());
        v.setCode(name.toUpperCase().replaceAll("[^A-Z]", "").substring(0, Math.min(6, name.length())));
        v.setName(name);
        return v;
    }

    /** An OPEN request in INR for 10 laptops and 10 monitors. */
    public static PurchaseRequest purchaseRequest(AppUser owner) {
        PurchaseRequest pr = new PurchaseRequest();
        pr.setId(nextId());
        pr.setRequestNumber("PR-2026-00001");
        pr.setTitle("Laptops for new joiners");
        pr.setEntity(entity());
        pr.setRequestedBy(owner);
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

    public static Quotation quotation(PurchaseRequest pr, Vendor vendor) {
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
