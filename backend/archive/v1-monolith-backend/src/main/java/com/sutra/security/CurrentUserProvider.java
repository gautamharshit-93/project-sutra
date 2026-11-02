package com.sutra.security;

import com.sutra.entity.AppUser;
import com.sutra.entity.enums.RoleName;
import com.sutra.exception.ForbiddenOperationException;
import com.sutra.repository.AppUserRepository;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Access to the authenticated user from the service layer. Mockable in unit tests. */
@Component
public class CurrentUserProvider {

    public static final String SYSTEM_USER = "system";

    private final AppUserRepository userRepository;

    public CurrentUserProvider(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

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

    /** The current user as a managed entity, for setting ownership foreign keys. */
    public AppUser requireUser() {
        return userRepository.getReferenceById(requirePrincipal().getId());
    }

    public String username() {
        return principal().map(SutraUserPrincipal::getUsername).orElse(SYSTEM_USER);
    }

    public boolean hasRole(RoleName role) {
        return principal().map(p -> p.hasRole(role)).orElse(false);
    }
}
