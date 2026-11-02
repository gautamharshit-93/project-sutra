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

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .map(AppUser::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
