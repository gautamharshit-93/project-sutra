package com.sutra.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Authentication and password payloads. */
public final class AuthRequests {

    private AuthRequests() {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    /** A supplier signing up on the vendor portal: its company details and the login it wants. */
    public record VendorSignupRequest(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 150) String contactPerson,
            @NotBlank @Email @Size(max = 255) String email,
            @Pattern(regexp = "^[0-9+()\\-\\s]{0,40}$", message = "invalid phone number") String phone,
            @Size(max = 50) String taxNumber,
            @Size(max = 1000) String address,
            @Size(max = 255) String website,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$", message = "3-60 letters, digits, dot, dash or underscore")
            String username,
            @NotBlank @Size(min = 8, max = 100)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
            String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 100)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
            String newPassword) {
    }
}
