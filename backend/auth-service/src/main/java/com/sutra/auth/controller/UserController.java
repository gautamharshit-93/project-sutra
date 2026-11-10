package com.sutra.auth.controller;

import com.sutra.auth.dto.request.UserRequest;
import com.sutra.common.web.PageResponse;
import com.sutra.auth.dto.response.UserResponses.RoleResponse;
import com.sutra.auth.dto.response.UserResponses.UserResponse;
import com.sutra.auth.dto.response.UserResponses.VendorSignupResponse;
import com.sutra.auth.service.UserService;
import com.sutra.auth.service.VendorSignupService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;
    private final VendorSignupService signups;

    public UserController(UserService userService, VendorSignupService signups) {
        this.userService = userService;
        this.signups = signups;
    }

    @GetMapping
    public PageResponse<UserResponse> search(@RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return userService.search(q, page, size);
    }

    @GetMapping("/roles")
    public List<RoleResponse> roles() {
        return userService.roles();
    }

    /** Vendors that signed up on the portal and wait for approval. */
    @GetMapping("/vendor-signups")
    public List<VendorSignupResponse> vendorSignups() {
        return signups.pending();
    }

    /** One click: creates the vendor in master data, links the login and activates it. */
    @PostMapping("/{id}/approve-vendor")
    public UserResponse approveVendor(@PathVariable Long id) {
        return signups.approve(id);
    }

    /** Rejects a sign-up: the waiting login and its details are deleted. */
    @DeleteMapping("/{id}/vendor-signup")
    public ResponseEntity<Void> rejectVendor(@PathVariable Long id) {
        signups.reject(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return userService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody UserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UserRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
