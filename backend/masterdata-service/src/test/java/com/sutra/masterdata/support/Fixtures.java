package com.sutra.masterdata.support;

import com.sutra.masterdata.entity.BusinessEntity;
import com.sutra.masterdata.entity.Vendor;
import java.util.concurrent.atomic.AtomicLong;

/** Builders for master-data unit tests. Entities get ids as if they had been persisted. */
public final class Fixtures {

    private static final AtomicLong IDS = new AtomicLong(1000);

    private Fixtures() {
    }

    public static BusinessEntity entity() {
        BusinessEntity e = new BusinessEntity();
        e.setId(IDS.incrementAndGet());
        e.setCode("HQ");
        e.setName("Head Office");
        return e;
    }

    public static Vendor vendor(String name) {
        Vendor v = new Vendor();
        v.setId(IDS.incrementAndGet());
        v.setCode(name.toUpperCase().replaceAll("[^A-Z]", "").substring(0, Math.min(6, name.length())));
        v.setName(name);
        return v;
    }
}
