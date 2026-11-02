package com.sutra.auth.config;

import com.sutra.auth.entity.AppUser;
import com.sutra.auth.entity.Role;
import com.sutra.common.security.RoleName;
import com.sutra.auth.repository.AppUserRepository;
import com.sutra.auth.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first administrator when the user table is empty. Roles come from migration V2. */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public DataInitializer(AppUserRepository userRepository, RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder, AuthProperties properties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        var cfg = properties.bootstrapAdmin();
        Role admin = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing - did the migrations run?"));
        AppUser user = new AppUser();
        user.setUsername(cfg.username());
        user.setEmail(cfg.email());
        user.setFullName("System Administrator");
        user.setPasswordHash(passwordEncoder.encode(cfg.password()));
        user.getRoles().add(admin);
        userRepository.save(user);
        log.info("Created bootstrap administrator '{}'. Change its password after first login.", cfg.username());
    }
}
