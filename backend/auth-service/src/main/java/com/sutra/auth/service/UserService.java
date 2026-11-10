package com.sutra.auth.service;

import com.sutra.auth.dto.request.UserRequest;
import com.sutra.auth.dto.response.UserResponses.RoleResponse;
import com.sutra.auth.dto.response.UserResponses.UserResponse;
import com.sutra.auth.entity.AppUser;
import com.sutra.common.web.PageResponse;
import java.util.List;

/** User accounts and role assignment (administrators only). */
public interface UserService {

    PageResponse<UserResponse> search(String q, int page, int size);

    UserResponse get(Long id);

    List<RoleResponse> roles();

    UserResponse create(UserRequest request);

    UserResponse update(Long id, UserRequest request);

    void delete(Long id);

    AppUser find(Long id);
}
