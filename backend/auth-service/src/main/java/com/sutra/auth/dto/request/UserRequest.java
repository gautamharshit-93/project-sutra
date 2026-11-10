package com.sutra.auth.dto.request;

import com.sutra.common.security.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Create/update a user. {@code password} is required on create and optional on update (blank = unchanged). */
public record UserRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$", message = "3-60 letters, digits, dot, dash or underscore")
        String username,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 150) String fullName,
        @Size(min = 8, max = 100)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
        String password,
        @NotEmpty Set<RoleName> roles,
        Boolean active,
        /** Required for (and only for) a VENDOR user: the supplier it logs in for. */
        Long vendorId) {
}
