package com.centry.security;

import com.centry.model.Role;
import com.centry.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Creates the first admin so a fresh install is never open and never locked out. Runs once all beans exist
 * (migrations done) but before the web server starts accepting requests. With any user present it does
 * nothing, so the property can stay set without ever overwriting a changed account.
 */
@Component
@RequiredArgsConstructor
class AdminBootstrap implements SmartInitializingSingleton {

    static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository users;
    private final AdminProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    public void afterSingletonsInstantiated() {
        if (users.count() > 0) {
            return;
        }
        String password = properties.password();
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("No users exist yet: set CENTRY_ADMIN_PASSWORD "
                    + "(centry.admin.password) to create the first admin account");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("CENTRY_ADMIN_PASSWORD (centry.admin.password) must be at least "
                    + MIN_PASSWORD_LENGTH + " characters");
        }
        users.insert(properties.username(), properties.username(), passwordEncoder.encode(password),
                Role.ADMIN, LocalDateTime.now(clock));
    }
}
