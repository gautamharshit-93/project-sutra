package com.sutra.auth.controller;

import com.sutra.auth.dto.request.AuthRequests.ChangePasswordRequest;
import com.sutra.auth.dto.request.AuthRequests.LoginRequest;
import com.sutra.auth.dto.request.AuthRequests.VendorSignupRequest;
import com.sutra.auth.dto.response.UserResponses.SignupAccepted;
import com.sutra.auth.service.VendorSignupService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.sutra.auth.dto.response.UserResponses.AuthResponse;
import com.sutra.auth.dto.response.UserResponses.UserResponse;
import com.sutra.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final VendorSignupService signups;

    public AuthController(AuthService authService, VendorSignupService signups) {
        this.authService = authService;
        this.signups = signups;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Public: a supplier signs up; the login stays inactive until an administrator approves it. */
    @PostMapping("/register-vendor")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupAccepted registerVendor(@Valid @RequestBody VendorSignupRequest request) {
        return signups.register(request);
    }

    @GetMapping("/me")
    public UserResponse me() {
        return authService.me();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.noContent().build();
    }
}
