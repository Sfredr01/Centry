package com.centry.repository;

import com.centry.model.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM users WHERE username LIKE 'lookup-%'").update();
    }

    @Test
    void findsIdByUsernameIgnoringCase() {
        long id = users.insert("lookup-Mia", "Mia", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();

        assertThat(users.idByUsername("LOOKUP-mia")).hasValue(id);
        assertThat(users.idByUsername("lookup-nobody")).isEmpty();
    }

    @Test
    void existsByIdIsTrueOnlyForStoredUsers() {
        long id = users.insert("lookup-ted", "Ted", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();

        assertThat(users.existsById(id)).isTrue();
        assertThat(users.existsById(999_999L)).isFalse();
    }

    @Test
    void displayNameIgnoresUsernameCase() {
        users.insert("lookup-sam", "Sam Rivera", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(users.displayName("LOOKUP-SAM")).hasValue("Sam Rivera");
        assertThat(users.displayName("lookup-nobody")).isEmpty();
    }
}
