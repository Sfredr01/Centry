package com.centry.repository;

import com.centry.model.Role;
import com.centry.model.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Reads never select password_hash; authentication reads it through Spring's own JdbcDaoImpl. */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
    }

    /**
     * @param passwordHash encoded hash, or null for a member who cannot log in
     * @throws UsernameTakenException if the username exists (case-insensitive)
     */
    public User insert(String username, String displayName, String passwordHash, Role role, LocalDateTime createdAt) {
        // DO NOTHING + RETURNING makes the duplicate check atomic and needs no driver-specific error parsing.
        return jdbc.sql("""
                        INSERT INTO users (username, display_name, password_hash, role, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (username) DO NOTHING
                        RETURNING id, username, display_name, role, created_at""")
                .params(username, displayName, passwordHash, role.name(), Cents.toText(createdAt))
                .query(UserRepository::map)
                .optional()
                .orElseThrow(() -> new UsernameTakenException(username));
    }

    public List<User> findAll() {
        return jdbc.sql("SELECT id, username, display_name, role, created_at FROM users ORDER BY id")
                .query(UserRepository::map).list();
    }

    /** The username column is NOCASE, so the lookup ignores case like the login does. */
    public OptionalLong idByUsername(String username) {
        return jdbc.sql("SELECT id FROM users WHERE username = ?").param(username).query(Long.class).optional()
                .map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    /** Same case rule as {@link #idByUsername}. */
    public Optional<String> displayName(String username) {
        return jdbc.sql("SELECT display_name FROM users WHERE username = ?").param(username).query(String.class)
                .optional();
    }

    public boolean existsById(long id) {
        return jdbc.sql("SELECT count(*) FROM users WHERE id = ?").param(id).query(Long.class).single() > 0;
    }

    private static User map(ResultSet rs, int row) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                Role.valueOf(rs.getString("role")), Cents.fromText(rs.getString("created_at")));
    }
}
