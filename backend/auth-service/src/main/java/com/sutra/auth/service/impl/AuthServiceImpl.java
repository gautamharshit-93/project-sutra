package com.sutra.auth.service.impl;

import com.sutra.auth.service.AuthService;
import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.auth.entity.AppUser;
import com.sutra.auth.exception.AccountLockedException;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.dto.request.AuthRequests.ChangePasswordRequest;
import com.sutra.auth.dto.request.AuthRequests.LoginRequest;
import com.sutra.auth.dto.response.UserResponses.AuthResponse;
import com.sutra.auth.dto.response.UserResponses.UserResponse;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.JwtCodec;
import com.sutra.auth.service.internal.LoginAttemptService;
import com.sutra.common.security.SutraUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtCodec jwt;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final LoginAttemptService loginAttempts;

    public AuthServiceImpl(AuthenticationManager authenticationManager, JwtCodec jwt,
                       AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                       CurrentUserProvider currentUser, AuditService auditService,
                       LoginAttemptService loginAttempts) {
        this.authenticationManager = authenticationManager;
        this.jwt = jwt;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.loginAttempts = loginAttempts;
    }

    /** Failed and blocked attempts are audited too, so the audit entry must survive the exception. */
    @Transactional(noRollbackFor = {AuthenticationException.class, AccountLockedException.class})
    @Override
    public AuthResponse login(LoginRequest request) {
        String username = request.username().trim();
        try {
            loginAttempts.requireNotLocked(username);
        } catch (AccountLockedException e) {
            auditService.recordAs(username, "USER", null, "LOGIN_BLOCKED", null);
            throw e;
        }
        // A vendor that signed up but is not approved yet gets a clear answer (only with the right password)
        userRepository.findByUsernameIgnoreCase(username)
                .filter(AppUser::isPendingApproval)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .ifPresent(u -> {
                    throw new BusinessRuleException("PENDING_APPROVAL", "Your vendor account is waiting for approval "
                            + "by the SUTRA administrator. You can sign in as soon as it is approved.");
                });
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (AuthenticationException e) {
            boolean locked = loginAttempts.recordFailure(username);
            auditService.recordAs(username, "USER", null, locked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", null);
            if (locked) {
                log.warn("Sign-in locked for a user after repeated failures");
            }
            throw e;
        }
        loginAttempts.recordSuccess(username);
        SutraUserPrincipal principal = (SutraUserPrincipal) auth.getPrincipal();
        String token = jwt.issue(principal);
        AppUser user = userRepository.findById(principal.getId()).orElseThrow();
        auditService.recordAs(user.getUsername(), "USER", user.getId(), "LOGIN", null);
        return new AuthResponse(token, jwt.verify(token).orElseThrow().expiresAt(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    @Override
    public UserResponse me() {
        return UserResponse.from(userRepository.findById(currentUser.requirePrincipal().getId()).orElseThrow());
    }

    @Transactional
    @Override
    public void changePassword(ChangePasswordRequest request) {
        AppUser user = userRepository.findById(currentUser.requirePrincipal().getId()).orElseThrow();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BusinessRuleException("PASSWORD_UNCHANGED", "The new password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordChangeRequired(false);
        user.revokeTokens(); // other sessions must sign in again with the new password
        auditService.record("USER", user.getId(), "PASSWORD_CHANGED", details());
    }
}
