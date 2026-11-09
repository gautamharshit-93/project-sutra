package com.sutra.auth.security;

import com.sutra.auth.entity.AppUser;

import com.sutra.auth.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SutraUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public SutraUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * An unknown username is an ordinary failed sign-in: it must not mark the caller's (login) transaction
     * rollback-only, or the failure audit is lost and the client gets a 500 instead of a 401.
     */
    @Override
    @Transactional(readOnly = true, noRollbackFor = UsernameNotFoundException.class)
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .map(AppUser::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
