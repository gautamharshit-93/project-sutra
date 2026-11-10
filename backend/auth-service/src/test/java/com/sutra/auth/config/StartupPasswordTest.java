package com.sutra.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sutra.auth.entity.AppUser;
import com.sutra.auth.entity.Role;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.repository.RoleRepository;
import com.sutra.auth.dto.request.AuthRequests.ChangePasswordRequest;
import com.sutra.auth.service.internal.LoginAttemptService;
import com.sutra.auth.service.AuthService;
import com.sutra.auth.service.impl.AuthServiceImpl;
import com.sutra.auth.support.Fixtures;
import com.sutra.common.audit.AuditService;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.JwtCodec;
import com.sutra.common.security.RoleName;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The administrator created at start-up must replace the known start-up password before doing anything else. */
class StartupPasswordTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AuthProperties properties = Fixtures.properties();
    private final DataInitializer initializer = new DataInitializer(users, roles, encoder, properties);

    @Test
    void theFirstAdministratorMustChooseANewPassword() {
        when(users.count()).thenReturn(0L);
        when(roles.findByName(RoleName.ADMIN)).thenReturn(Optional.of(new Role(RoleName.ADMIN)));
        AtomicReference<AppUser> saved = new AtomicReference<>();
        when(users.save(any())).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        initializer.run(null);

        assertThat(saved.get().isPasswordChangeRequired()).isTrue();
    }

    @Test
    void anAdministratorStillOnTheStartupPasswordIsAskedAgainAfterARestart() {
        AppUser admin = Fixtures.user(properties.bootstrapAdmin().username(), RoleName.ADMIN);
        admin.setPasswordHash(encoder.encode(properties.bootstrapAdmin().password()));
        when(users.count()).thenReturn(1L);
        when(users.findByUsernameIgnoreCase(admin.getUsername())).thenReturn(Optional.of(admin));

        initializer.run(null);

        assertThat(admin.isPasswordChangeRequired()).isTrue();
    }

    @Test
    void anAdministratorWithItsOwnPasswordIsLeftAlone() {
        AppUser admin = Fixtures.user(properties.bootstrapAdmin().username(), RoleName.ADMIN);
        admin.setPasswordHash(encoder.encode("Chosen#Pass42"));
        when(users.count()).thenReturn(1L);
        when(users.findByUsernameIgnoreCase(admin.getUsername())).thenReturn(Optional.of(admin));

        initializer.run(null);

        assertThat(admin.isPasswordChangeRequired()).isFalse();
    }

    @Test
    void changingThePasswordClearsTheRequirement() {
        AppUser admin = Fixtures.user("admin", RoleName.ADMIN);
        admin.setPasswordHash(encoder.encode("Admin@123"));
        admin.setPasswordChangeRequired(true);
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.requirePrincipal()).thenReturn(admin.toPrincipal());
        when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
        AuthService auth = new AuthServiceImpl(mock(AuthenticationManager.class), mock(JwtCodec.class), users, encoder,
                currentUser, mock(AuditService.class), mock(LoginAttemptService.class));

        auth.changePassword(new ChangePasswordRequest("Admin@123", "Chosen#Pass42"));

        assertThat(admin.isPasswordChangeRequired()).isFalse();
        assertThat(encoder.matches("Chosen#Pass42", admin.getPasswordHash())).isTrue();
    }
}
