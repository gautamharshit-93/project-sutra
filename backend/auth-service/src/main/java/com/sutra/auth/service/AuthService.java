package com.sutra.auth.service;

import com.sutra.auth.dto.request.AuthRequests.ChangePasswordRequest;
import com.sutra.auth.dto.request.AuthRequests.LoginRequest;
import com.sutra.auth.dto.response.UserResponses.AuthResponse;
import com.sutra.auth.dto.response.UserResponses.UserResponse;

public interface AuthService {

    AuthResponse login(LoginRequest request);

    UserResponse me();

    void changePassword(ChangePasswordRequest request);
}
