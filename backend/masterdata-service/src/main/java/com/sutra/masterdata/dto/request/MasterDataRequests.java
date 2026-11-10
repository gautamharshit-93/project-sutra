package com.sutra.masterdata.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Payloads for entities, sub-entities, customers and vendors. */
public final class MasterDataRequests {

    private static final String CODE_REGEX = "^[A-Za-z0-9_-]{2,30}$";
    private static final String CODE_MSG = "2-30 letters, digits, dash or underscore";
    private static final String PHONE_REGEX = "^[0-9+()\\-\\s]{0,40}$";

    private MasterDataRequests() {
    }

    /** {@code code} may be left blank on create: one is made from the name. */
    public record EntityRequest(
            @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Size(max = 1000) String address,
            Boolean active) {
    }

    public record SubEntityRequest(
            @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            Boolean active) {
    }

    public record CustomerRequest(
            @NotBlank @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 200) String name,
            Long entityId,
            @Size(max = 150) String contactPerson,
            @Email @Size(max = 255) String email,
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String phone,
            @Size(max = 1000) String address,
            Boolean active) {
    }

    public record VendorRequest(
            @NotBlank @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 150) String contactPerson,
            @Email @Size(max = 255) String email,
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String phone,
            @Size(max = 1000) String address,
            @Size(max = 50) String taxNumber,
            @Size(max = 255) String website,
            Boolean active) {
    }

    /** A vendor that signed up on the portal and was approved (sent by the auth service; the code is generated). */
    public record VendorSignupRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 150) String contactPerson,
            @Email @Size(max = 255) String email,
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String phone,
            @Size(max = 1000) String address,
            @Size(max = 50) String taxNumber,
            @Size(max = 255) String website) {
    }

    /** What a vendor may change about itself on the portal (the name and code stay as approved). */
    public record VendorProfileRequest(
            @Size(max = 150) String contactPerson,
            @Email @Size(max = 255) String email,
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String phone,
            @Size(max = 1000) String address,
            @Size(max = 50) String taxNumber,
            @Size(max = 255) String website) {
    }
}
