package com.sutra.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.auth.client.MasterDataClient;
import com.sutra.auth.entity.AppUser;
import com.sutra.auth.entity.Role;
import com.sutra.auth.entity.VendorSignup;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.repository.RoleRepository;
import com.sutra.auth.repository.VendorSignupRepository;
import com.sutra.auth.dto.request.AuthRequests.VendorSignupRequest;
import com.sutra.auth.support.Fixtures;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.security.RoleName;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Vendors sign up themselves; nothing is visible to them until an administrator approves with one click. */
@ExtendWith(MockitoExtension.class)
class VendorSignupServiceImplTest {

    @Mock AppUserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock VendorSignupRepository signupRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock MasterDataClient masterData;
    @Mock AuditService auditService;
    @InjectMocks VendorSignupServiceImpl service;

    private static VendorSignupRequest request() {
        return new VendorSignupRequest("Shakti Metals", "Ravi Kumar", "Sales@Shakti.example", "+91 98765 43210",
                "27ABCDE1234F1Z5", "Pune", null, "Shakti.Sales", "Secret123");
    }

    @Test
    void signUpCreatesAnInactiveVendorLoginWaitingForApproval() {
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of(new Role(RoleName.VENDOR)));
        when(passwordEncoder.encode("Secret123")).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var accepted = service.register(request());

        assertThat(accepted.username()).isEqualTo("shakti.sales");
        assertThat(accepted.message()).contains("waiting for approval");
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u -> !u.isActive() && u.isPendingApproval()
                && u.hasRole(RoleName.VENDOR) && u.getVendorId() == null && u.getEmail().equals("sales@shakti.example")));
        verify(signupRepository).save(org.mockito.ArgumentMatchers.argThat(s -> s.getCompanyName().equals("Shakti Metals")));
    }

    @Test
    void takenUsernameIsRejected() {
        when(userRepository.existsByUsernameIgnoreCase("shakti.sales")).thenReturn(true);
        assertThatThrownBy(() -> service.register(request())).isInstanceOf(DuplicateResourceException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void approvalCreatesTheVendorAndActivatesTheLogin() {
        AppUser user = Fixtures.user("shakti.sales", RoleName.VENDOR);
        user.setActive(false);
        user.setPendingApproval(true);
        VendorSignup signup = new VendorSignup();
        signup.setUser(user);
        signup.setCompanyName("Shakti Metals");
        when(userRepository.existsById(user.getId())).thenReturn(true);
        when(signupRepository.findByUserId(user.getId())).thenReturn(Optional.of(signup));
        when(masterData.registerVendor(any())).thenReturn(new MasterDataClient.VendorSnapshot(9L, "SHAKTIMETA",
                "Shakti Metals", true));

        var approved = service.approve(user.getId());

        assertThat(approved.active()).isTrue();
        assertThat(approved.pendingApproval()).isFalse();
        assertThat(approved.vendorId()).isEqualTo(9L);
        assertThat(approved.vendorName()).isEqualTo("Shakti Metals");
        verify(signupRepository).delete(signup);
    }

    @Test
    void onlyWaitingSignUpsCanBeApproved() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(signupRepository.findByUserId(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(5L)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("NO_PENDING_SIGNUP");
        verify(masterData, never()).registerVendor(any());
    }
}
