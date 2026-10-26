package com.centry.repository;

import com.centry.model.ApiToken;
import com.centry.model.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Stores only token hashes; the plain token exists once, in the response that issues it. */
@Repository
public class ApiTokenRepository {

    /** Who a token authenticates as. */
    public record Owner(long tokenId, String username, Role role) {
    }

    private final JdbcClient jdbc;

    public ApiTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, String name, String tokenHash, LocalDateTime createdAt) {
        return jdbc.sql("""
                        INSERT INTO api_token (user_id, name, token_hash, created_at) VALUES (?, ?, ?, ?)
                        RETURNING id""")
                .params(userId, name, tokenHash, Cents.toText(createdAt))
                .query(Long.class).single();
    }

    public List<ApiToken> findByUser(long userId) {
        return jdbc.sql("SELECT id, name, created_at, last_used_at FROM api_token WHERE user_id = ? ORDER BY id")
                .param(userId).query(ApiTokenRepository::map).list();
    }

    /** A user without a password hash cannot log in, so their tokens do not authenticate either. */
    public Optional<Owner> findOwner(String tokenHash) {
        return jdbc.sql("""
                        SELECT t.id, u.username, u.role FROM api_token t JOIN users u ON u.id = t.user_id
                        WHERE t.token_hash = ? AND u.password_hash IS NOT NULL""")
                .param(tokenHash)
                .query((rs, row) -> new Owner(rs.getLong("id"), rs.getString("username"),
                        Role.valueOf(rs.getString("role"))))
                .optional();
    }

    public void touch(long id, LocalDateTime usedAt) {
        jdbc.sql("UPDATE api_token SET last_used_at = ? WHERE id = ?").params(Cents.toText(usedAt), id).update();
    }

    /** @return false when the token does not exist or belongs to someone else */
    public boolean delete(long id, long userId) {
        return jdbc.sql("DELETE FROM api_token WHERE id = ? AND user_id = ?").params(id, userId).update() > 0;
    }

    private static ApiToken map(ResultSet rs, int row) throws SQLException {
        String lastUsed = rs.getString("last_used_at");
        return new ApiToken(rs.getLong("id"), rs.getString("name"), Cents.fromText(rs.getString("created_at")),
                lastUsed == null ? null : Cents.fromText(lastUsed));
    }
}
