package com.centry.repository;

import com.centry.model.ApiToken;
import com.centry.model.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ApiTokenRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 10, 0);
    private static final String HASH = "a".repeat(64);

    @Autowired
    private ApiTokenRepository tokens;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM users WHERE username LIKE 'tok-%'").update();
    }

    private long user(String name, String passwordHash) {
        return users.insert(name, name, passwordHash, Role.MEMBER, NOW).id();
    }

    @Test
    void storesTheHashAndNeverAnythingElseSecret() {
        long owner = user("tok-mia", "{noop}x");
        long id = tokens.insert(owner, "phone", HASH, NOW);

        assertThat(jdbc.sql("SELECT token_hash FROM api_token WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo(HASH);
        assertThat(tokens.findByUser(owner)).containsExactly(new ApiToken(id, "phone", NOW, null));
    }

    @Test
    void duplicateHashIsRejected() {
        long owner = user("tok-mia", "{noop}x");
        tokens.insert(owner, "one", HASH, NOW);

        assertThatThrownBy(() -> tokens.insert(owner, "two", HASH, NOW))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE");
    }

    @Test
    void deletingTheUserDeletesTheirTokens() {
        long owner = user("tok-mia", "{noop}x");
        tokens.insert(owner, "one", HASH, NOW);

        jdbc.sql("DELETE FROM users WHERE id = ?").param(owner).update();

        assertThat(jdbc.sql("SELECT count(*) FROM api_token").query(Integer.class).single()).isZero();
    }

    @Test
    void findsOwnerByHashOnlyWhenTheUserCanLogIn() {
        long mia = user("tok-mia", "{noop}x");
        long kid = user("tok-kid", null);
        long miaToken = tokens.insert(mia, "one", HASH, NOW);
        tokens.insert(kid, "two", "b".repeat(64), NOW);

        assertThat(tokens.findOwner(HASH)).hasValueSatisfying(owner -> {
            assertThat(owner.tokenId()).isEqualTo(miaToken);
            assertThat(owner.username()).isEqualTo("tok-mia");
            assertThat(owner.role()).isEqualTo(Role.MEMBER);
        });
        assertThat(tokens.findOwner("b".repeat(64))).isEmpty();
        assertThat(tokens.findOwner("c".repeat(64))).isEmpty();
    }

    @Test
    void touchRecordsLastUse() {
        long owner = user("tok-mia", "{noop}x");
        long id = tokens.insert(owner, "one", HASH, NOW);

        tokens.touch(id, NOW.plusHours(1));

        assertThat(tokens.findByUser(owner)).extracting(ApiToken::lastUsedAt).containsExactly(NOW.plusHours(1));
    }

    @Test
    void deleteOnlyRemovesTheOwnersToken() {
        long mia = user("tok-mia", "{noop}x");
        long ted = user("tok-ted", "{noop}x");
        long id = tokens.insert(mia, "one", HASH, NOW);

        assertThat(tokens.delete(id, ted)).isFalse();
        assertThat(tokens.delete(id, mia)).isTrue();
        assertThat(tokens.delete(id, mia)).isFalse();
    }
}
