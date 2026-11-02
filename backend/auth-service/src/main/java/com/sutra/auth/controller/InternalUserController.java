package com.sutra.auth.controller;

import com.sutra.auth.repository.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API (service tokens only; the gateway never forwards {@code /internal}). */
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    /** Whether a user's tokens are still valid: the gateway compares the version with the token's. */
    public record TokenState(Long userId, boolean active, long tokenVersion) {
    }

    private final AppUserRepository userRepository;

    public InternalUserController(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/{id}/token-state")
    @Transactional(readOnly = true)
    public ResponseEntity<TokenState> tokenState(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(u -> ResponseEntity.ok(new TokenState(u.getId(), u.isActive(), u.getTokenVersion())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
