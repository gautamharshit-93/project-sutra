package com.sutra.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.entity.AppUser;
import com.sutra.entity.Role;
import com.sutra.entity.enums.RoleName;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.DuplicateResourceException;
import com.sutra.repository.AppUserRepository;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.repository.QuotationRepository;
import com.sutra.repository.RoleRepository;
import com.sutra.request.UserRequest;
import com.sutra.security.CurrentUserProvider;
import com.sutra.security.LoginAttemptService;
import com.sutra.support.Fixtures;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock AppUserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock PurchaseRequestRepository purchaseRequestRepository;
    @Mock QuotationRepository quotationRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;
    @Mock LoginAttemptService loginAttempts;
    @InjectMocks UserService service;

    @Test
    void createsUserWithHashedPasswordAndRoles() {
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of(new Role(RoleName.PROCUREMENT)));
        when(passwordEncoder.encode("Secret123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(new UserRequest("Buyer.One", "Buyer@Corp.com", "Buyer One", "Secret123",
                Set.of(RoleName.PROCUREMENT), null));

        assertThat(response.username()).isEqualTo("buyer.one");
        assertThat(response.email()).isEqualTo("buyer@corp.com");
        assertThat(response.roles()).containsExactly(RoleName.PROCUREMENT);
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> u.getPasswordHash().equals("bcrypt-hash")));
    }

    @Test
    void newUserNeedsAPassword() {
        assertThatThrownBy(() -> service.create(new UserRequest("buyer", "b@c.com", "B", null,
                Set.of(RoleName.VIEWER), true)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("PASSWORD_REQUIRED");
    }

    @Test
    void duplicateUsernameIsRejected() {
        when(userRepository.existsByUsernameIgnoreCase("buyer")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new UserRequest("buyer", "b@c.com", "B", "Secret123",
                Set.of(RoleName.VIEWER), true))).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void adminCannotRemoveOwnAdminRole() {
        AppUser admin = Fixtures.user("admin", RoleName.ADMIN);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(admin));

        assertThatThrownBy(() -> service.update(admin.getId(), new UserRequest("admin", "admin@test", "Admin", null,
                Set.of(RoleName.VIEWER), true)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("SELF_LOCKOUT");
    }

    @Test
    void userOwningRecordsIsDeactivatedNotDeleted() {
        AppUser buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(1L, RoleName.ADMIN));
        when(purchaseRequestRepository.countByRequestedById(buyer.getId())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(buyer.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("USER_IN_USE");
        verify(userRepository, never()).delete(any());
    }
}
