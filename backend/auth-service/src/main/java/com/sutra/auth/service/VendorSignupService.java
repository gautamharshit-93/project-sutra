package com.sutra.auth.service;

import com.sutra.auth.dto.request.AuthRequests.VendorSignupRequest;
import com.sutra.auth.dto.response.UserResponses.SignupAccepted;
import com.sutra.auth.dto.response.UserResponses.UserResponse;
import com.sutra.auth.dto.response.UserResponses.VendorSignupResponse;
import java.util.List;

/**
 * Vendors sign up themselves on the portal; an administrator approves each one with a single click. Until then the
 * login is inactive, so a stranger who signs up never sees the company's RFQs.
 */
public interface VendorSignupService {

    SignupAccepted register(VendorSignupRequest request);

    List<VendorSignupResponse> pending();

    UserResponse approve(Long userId);

    void reject(Long userId);
}
