package org.Roclh.service;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.User;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.event.UserCreatedEvent;
import org.Roclh.service.event.UserRenamedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    public boolean usernameTaken(String username, UUID excludeId) {
        return userRepository.findByUsername(username)
                .filter(u -> excludeId == null || !u.getId().equals(excludeId))
                .isPresent();
    }

    @Transactional
    public User create(String username, String rawPassword, String role, boolean enabled) {
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(enabled)
                .build();
        User saved = userRepository.save(user);
        events.publishEvent(new UserCreatedEvent(saved.getId(), saved.getUsername()));
        return saved;
    }

    @Transactional
    public User update(UUID id, String username, String rawPasswordOrNull, String role, boolean enabled) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        String previousUsername = user.getUsername();
        user.setUsername(username);
        user.setRole(role);
        user.setEnabled(enabled);
        if (rawPasswordOrNull != null && !rawPasswordOrNull.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(rawPasswordOrNull));
        }
        user = userRepository.save(user);
        if (!previousUsername.equals(username)) {
            events.publishEvent(new UserRenamedEvent(user.getId(), username));
        }
        return user;
    }

    @Transactional
    public void delete(UUID id) {
        userRepository.deleteById(id);
    }

    public long countAdmins() {
        return userRepository.findAll().stream()
                .filter(u -> ROLE_ADMIN.equals(u.getRole()))
                .count();
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }
}