package com.sutra.common.security;

import com.sutra.common.error.ForbiddenOperationException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Access to the authenticated caller from the service layer. Mockable in unit tests. */
@Component
public class CurrentUserProvider {

    public static final String SYSTEM_USER = "system";

    public Optional<SutraUserPrincipal> principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SutraUserPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public SutraUserPrincipal requirePrincipal() {
        return principal().orElseThrow(() -> new ForbiddenOperationException("Authentication required"));
    }

    public String username() {
        return principal().map(SutraUserPrincipal::getUsername).orElse(SYSTEM_USER);
    }

    public boolean hasRole(RoleName role) {
        return principal().map(p -> p.hasRole(role)).orElse(false);
    }
}
