package com.sutra.auth.service.impl;

import com.sutra.common.audit.AuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.auth.entity.AppUser;
import com.sutra.auth.entity.Role;
import com.sutra.common.security.RoleName;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.client.MasterDataClient;
import com.sutra.auth.client.ProcurementClient;
import com.sutra.auth.repository.RoleRepository;
import com.sutra.auth.dto.request.UserRequest;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.auth.service.internal.LoginAttemptService;
import com.sutra.auth.support.Fixtures;
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
class UserServiceImplTest {

    @Mock AppUserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;
    @Mock LoginAttemptService loginAttempts;
    @Mock MasterDataClient masterData;
    @Mock ProcurementClient procurement;
    @InjectMocks UserServiceImpl service;

    @Test
    void createsUserWithHashedPasswordAndRoles() {
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of(new Role(RoleName.PROCUREMENT)));
        when(passwordEncoder.encode("Secret123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(new UserRequest("Buyer.One", "Buyer@Corp.com", "Buyer One", "Secret123",
                Set.of(RoleName.PROCUREMENT), null, null));

        assertThat(response.username()).isEqualTo("buyer.one");
        assertThat(response.email()).isEqualTo("buyer@corp.com");
        assertThat(response.roles()).containsExactly(RoleName.PROCUREMENT);
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> u.getPasswordHash().equals("bcrypt-hash")));
    }

    @Test
    void newUserNeedsAPassword() {
        assertThatThrownBy(() -> service.create(new UserRequest("buyer", "b@c.com", "B", null,
                Set.of(RoleName.APPROVER), true, null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("PASSWORD_REQUIRED");
    }

    @Test
    void duplicateUsernameIsRejected() {
        when(userRepository.existsByUsernameIgnoreCase("buyer")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new UserRequest("buyer", "b@c.com", "B", "Secret123",
                Set.of(RoleName.APPROVER), true, null))).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void adminCannotRemoveOwnAdminRole() {
        AppUser admin = Fixtures.user("admin", RoleName.ADMIN);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(admin));

        assertThatThrownBy(() -> service.update(admin.getId(), new UserRequest("admin", "admin@test", "Admin", null,
                Set.of(RoleName.APPROVER), true, null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("SELF_LOCKOUT");
    }

    @Test
    void userOwningRecordsIsDeactivatedNotDeleted() {
        AppUser buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(1L, RoleName.ADMIN));
        when(procurement.userUsage(buyer.getId())).thenReturn(new ProcurementClient.Usage(2, 0));

        assertThatThrownBy(() -> service.delete(buyer.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("USER_IN_USE");
        verify(userRepository, never()).delete(any());
    }

    @Test
    void vendorLoginIsLinkedToAnActiveVendorFromMasterData() {
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of(new Role(RoleName.VENDOR)));
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(masterData.activeVendor(7L)).thenReturn(new MasterDataClient.VendorSnapshot(7L, "BRK", "Brakeline Ltd", true));

        var response = service.create(new UserRequest("brakeline", "sales@brakeline.test", "Brakeline Sales",
                "Secret123", Set.of(RoleName.VENDOR), true, 7L));

        assertThat(response.vendorId()).isEqualTo(7L);
        assertThat(response.vendorName()).isEqualTo("Brakeline Ltd");
    }

    @Test
    void vendorLoginNeedsAVendorAndNoCompanyRole() {
        when(roleRepository.findByNameIn(anyCollection())).thenAnswer(inv -> ((java.util.Collection<RoleName>) inv
                .getArgument(0)).stream().map(Role::new).toList());
        assertThatThrownBy(() -> service.create(new UserRequest("brakeline", "s@b.test", "B", "Secret123",
                Set.of(RoleName.VENDOR), true, null)))
                .extracting("code").isEqualTo("VENDOR_REQUIRED");
        assertThatThrownBy(() -> service.create(new UserRequest("brakeline", "s@b.test", "B", "Secret123",
                Set.of(RoleName.VENDOR, RoleName.PROCUREMENT), true, 7L)))
                .extracting("code").isEqualTo("VENDOR_ROLE_EXCLUSIVE");
        assertThatThrownBy(() -> service.create(new UserRequest("buyer", "b@c.test", "B", "Secret123",
                Set.of(RoleName.PROCUREMENT), true, 7L)))
                .extracting("code").isEqualTo("VENDOR_ROLE_REQUIRED");
    }

    @Test
    void anyChangeRevokesTheUsersExistingTokens() {
        AppUser buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        long before = buyer.getTokenVersion();
        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(1L, RoleName.ADMIN));
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of(new Role(RoleName.APPROVER)));

        service.update(buyer.getId(), new UserRequest("buyer", "buyer@test", "Buyer", null,
                Set.of(RoleName.APPROVER), false, null));

        assertThat(buyer.getTokenVersion()).isEqualTo(before + 1);
        assertThat(buyer.isActive()).isFalse();
    }

    @Test
    void unusedUserIsDeleted() {
        AppUser buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(1L, RoleName.ADMIN));
        when(procurement.userUsage(buyer.getId())).thenReturn(new ProcurementClient.Usage(0, 0));

        service.delete(buyer.getId());

        verify(userRepository).delete(buyer);
    }

    @Test
    void aWaitingVendorSignUpCannotBeActivatedThroughEditUser() {
        AppUser waiting = Fixtures.user("kaveri", RoleName.VENDOR);
        waiting.setActive(false);
        waiting.setPendingApproval(true);
        when(userRepository.findById(waiting.getId())).thenReturn(Optional.of(waiting));

        assertThatThrownBy(() -> service.update(waiting.getId(), new UserRequest("kaveri", "k@x.in", "Kaveri", null,
                Set.of(RoleName.VENDOR), true, 5L)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("SIGNUP_PENDING");
        assertThat(waiting.isActive()).isFalse();
    }
}
