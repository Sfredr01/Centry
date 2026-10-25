package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class ExpenseRepository {

    private final JdbcClient jdbc;

    public ExpenseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Spring has no error-code table for SQLite (the driver reports plain 19 and no SQL state), so a
    // UNIQUE violation surfaces as "uncategorized"; the driver's result-code name is the stable marker.
    private static final String SQLITE_CONSTRAINT_UNIQUE = "SQLITE_CONSTRAINT_UNIQUE";

    /** @throws DuplicateKeyException if the transaction id is already stored */
    public ExpenseEntity save(ExpenseEntity e) {
        var keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO expense (transaction_id, merchant, amount_cents, category, occurred_at)
                            VALUES (?, ?, ?, ?, ?)""")
                    .params(e.transactionId(), e.merchant(), Cents.toCents(e.amount()), e.category(),
                            Cents.toText(e.timestamp()))
                    .update(keys);
        } catch (UncategorizedSQLException ex) {
            if (ex.getSQLException().getMessage().contains(SQLITE_CONSTRAINT_UNIQUE)) {
                throw new DuplicateKeyException("Duplicate transaction id " + e.transactionId(), ex);
            }
            throw ex;
        }
        return new ExpenseEntity(keys.getKey().longValue(), e.transactionId(), e.merchant(), e.amount(),
                e.category(), e.timestamp());
    }

    private static final String SELECT =
            "SELECT id, transaction_id, merchant, amount_cents, category, occurred_at FROM expense";

    public List<ExpenseEntity> findAll() {
        return jdbc.sql(SELECT + " ORDER BY id").query(ExpenseRepository::map).list();
    }

    /** The latest expenses, newest date first; ties (same timestamp) go to the one recorded last. */
    public List<ExpenseEntity> findRecent(int limit) {
        return jdbc.sql(SELECT + " ORDER BY occurred_at DESC, id DESC LIMIT ?").param(limit)
                .query(ExpenseRepository::map).list();
    }

    /** Categories already used, one per normalized key (see {@code Keys.of}) in the latest spelling, newest first. */
    public List<String> recentCategories(int limit) {
        return jdbc.sql("""
                        SELECT category FROM expense
                         WHERE id IN (SELECT MAX(id) FROM expense GROUP BY lower(trim(category)))
                         ORDER BY id DESC LIMIT ?""")
                .param(limit).query(String.class).list();
    }

    public Optional<ExpenseEntity> findById(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(ExpenseRepository::map).optional();
    }

    public Optional<ExpenseEntity> findByTransactionId(String transactionId) {
        return jdbc.sql(SELECT + " WHERE transaction_id = ?").param(transactionId)
                .query(ExpenseRepository::map).optional();
    }

    /**
     * The merchant's latest earlier expense by id (insertion order), excluding the current row. The key is
     * the normalized merchant (see {@code Keys.of}); uses idx_expense_merchant_key_id.
     */
    public Optional<ExpenseEntity> findPreviousForMerchant(String merchantKey, long currentId) {
        return jdbc.sql(SELECT + " WHERE lower(trim(merchant)) = ? AND id < ? ORDER BY id DESC LIMIT 1")
                .params(merchantKey, currentId).query(ExpenseRepository::map).optional();
    }

    private static ExpenseEntity map(ResultSet rs, int row) throws SQLException {
        return new ExpenseEntity(
                rs.getLong("id"),
                rs.getString("transaction_id"),
                rs.getString("merchant"),
                Cents.money(rs, "amount_cents"),
                rs.getString("category"),
                Cents.fromText(rs.getString("occurred_at")));
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM expense").query(Long.class).single();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM expense").update();
    }
}
