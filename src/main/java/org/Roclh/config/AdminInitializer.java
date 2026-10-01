package org.Roclh.config;

import org.Roclh.model.User;
import org.Roclh.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "changeme";

    @Bean
    public ApplicationRunner seedAdmin(UserRepository userRepository, PasswordEncoder encoder) {
        return args -> {
            if (userRepository.existsByUsername(DEFAULT_ADMIN_USERNAME)) {
                return;
            }
            User admin = new User();
            admin.setUsername(DEFAULT_ADMIN_USERNAME);
            admin.setPasswordHash(encoder.encode(DEFAULT_ADMIN_PASSWORD));
            admin.setRole("ADMIN");
            admin.setEnabled(true);
            userRepository.save(admin);
            log.warn("Created default admin '{}' with password '{}'. CHANGE IT IMMEDIATELY.",
                    DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
        };
    }
}