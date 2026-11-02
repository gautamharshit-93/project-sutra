package com.sutra.service;

import static com.sutra.service.AuditService.details;

import com.sutra.entity.AppUser;
import com.sutra.exception.AccountLockedException;
import com.sutra.exception.BusinessRuleException;
import com.sutra.repository.AppUserRepository;
import com.sutra.request.AuthRequests.ChangePasswordRequest;
import com.sutra.request.AuthRequests.LoginRequest;
import com.sutra.response.UserResponses.AuthResponse;
import com.sutra.response.UserResponses.UserResponse;
import com.sutra.security.CurrentUserProvider;
import com.sutra.security.JwtService;
import com.sutra.security.LoginAttemptService;
import com.sutra.security.SutraUserPrincipal;
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
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUser;
    private final AuditService auditService;
    private final LoginAttemptService loginAttempts;

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService,
                       AppUserRepository userRepository, PasswordEncoder passwordEncoder,
                       CurrentUserProvider currentUser, AuditService auditService,
                       LoginAttemptService loginAttempts) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.loginAttempts = loginAttempts;
    }

    /** Failed and blocked attempts are audited too, so the audit entry must survive the exception. */
    @Transactional(noRollbackFor = {AuthenticationException.class, AccountLockedException.class})
    public AuthResponse login(LoginRequest request) {
        String username = request.username().trim();
        try {
            loginAttempts.requireNotLocked(username);
        } catch (AccountLockedException e) {
            auditService.recordAs(username, "USER", null, "LOGIN_BLOCKED", null);
            throw e;
        }
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
        String token = jwtService.issue(principal);
        AppUser user = userRepository.findById(principal.getId()).orElseThrow();
        auditService.recordAs(user.getUsername(), "USER", user.getId(), "LOGIN", null);
        return new AuthResponse(token, jwtService.expiryOf(token), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me() {
        return UserResponse.from(userRepository.findById(currentUser.requirePrincipal().getId()).orElseThrow());
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        AppUser user = userRepository.findById(currentUser.requirePrincipal().getId()).orElseThrow();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BusinessRuleException("PASSWORD_UNCHANGED", "The new password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        auditService.record("USER", user.getId(), "PASSWORD_CHANGED", details());
    }
}
