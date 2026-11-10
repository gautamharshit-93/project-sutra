package com.sutra.auth.service.impl;

import com.sutra.common.audit.AuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.auth.entity.AppUser;
import com.sutra.common.security.RoleName;
import com.sutra.auth.exception.AccountLockedException;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.dto.request.AuthRequests.LoginRequest;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.JwtCodec;
import com.sutra.auth.service.internal.LoginAttemptService;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.auth.support.Fixtures;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock AuthenticationManager authenticationManager;
    @Mock JwtCodec jwt;
    @Mock AppUserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;
    @Mock LoginAttemptService loginAttempts;
    @InjectMocks AuthServiceImpl service;

    @Test
    void successfulLoginIssuesATokenAndResetsFailures() {
        AppUser user = Fixtures.user("asha", RoleName.PROCUREMENT);
        SutraUserPrincipal principal = user.toPrincipal();
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(jwt.issue(principal)).thenReturn("jwt");
        when(jwt.verify("jwt")).thenReturn(java.util.Optional.of(
                new JwtCodec.Token(JwtCodec.TYPE_USER, principal, Instant.parse("2026-11-01T14:00:00Z"))));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var response = service.login(new LoginRequest("  asha ", "secret"));

        assertThat(response.token()).isEqualTo("jwt");
        verify(loginAttempts).recordSuccess("asha");
        verify(auditService).recordAs("asha", "USER", user.getId(), "LOGIN", null);
    }

    @Test
    void wrongPasswordIsCountedAndAudited() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.login(new LoginRequest("asha", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginAttempts).recordFailure("asha");
        verify(auditService).recordAs("asha", "USER", null, "LOGIN_FAILED", null);
    }

    @Test
    void fifthFailureIsAuditedAsALockout() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
        when(loginAttempts.recordFailure("asha")).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("asha", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditService).recordAs("asha", "USER", null, "ACCOUNT_LOCKED", null);
    }

    @Test
    void lockedAccountIsRejectedWithoutCheckingThePassword() {
        doThrow(new AccountLockedException("locked")).when(loginAttempts).requireNotLocked("asha");

        assertThatThrownBy(() -> service.login(new LoginRequest("asha", "Correct@1")))
                .isInstanceOf(AccountLockedException.class);

        verify(authenticationManager, never()).authenticate(any());
        verify(auditService).recordAs("asha", "USER", null, "LOGIN_BLOCKED", null);
    }
}
