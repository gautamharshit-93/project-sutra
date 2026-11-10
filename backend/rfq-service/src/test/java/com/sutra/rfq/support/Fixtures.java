package com.sutra.rfq.support;

import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.rfq.entity.NamedRef;
import com.sutra.rfq.entity.PurchaseRequest;
import com.sutra.rfq.entity.RequestItem;
import com.sutra.rfq.entity.UserRef;
import com.sutra.api.enums.PurchaseRequestStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builders for RFQ unit tests. Entities get ids as if they had been persisted. Users are the signed-in
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
}
