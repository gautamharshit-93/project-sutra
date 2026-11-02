package com.sutra.auth.support;

import com.sutra.auth.config.AuthProperties;
import com.sutra.auth.entity.AppUser;
import com.sutra.auth.entity.Role;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Builders for auth-service unit tests. Entities get ids as if they had been persisted. */
public final class Fixtures {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC);

    private static final AtomicLong IDS = new AtomicLong(1000);

    private Fixtures() {
    }

    /** First administrator admin/Admin@123; sign-in locks after 5 failures for 15 minutes. */
    public static AuthProperties properties() {
        return new AuthProperties(new AuthProperties.BootstrapAdmin("admin", "Admin@123", "admin@test"), 5, 15);
    }

    public static AppUser user(String username, RoleName... roles) {
        AppUser user = new AppUser();
        user.setId(IDS.incrementAndGet());
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
        return user.toPrincipal();
    }

    public static SutraUserPrincipal principal(Long id, RoleName... roles) {
        return new SutraUserPrincipal(id, "user" + id, "hash", true, Set.of(roles));
    }
}
