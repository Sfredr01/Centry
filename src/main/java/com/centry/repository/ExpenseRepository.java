package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ExpenseRepository {

    private final JdbcClient jdbc;

    public ExpenseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ExpenseEntity save(ExpenseEntity e) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO expense (transaction_id, merchant, amount_cents, category, occurred_at)
                        VALUES (?, ?, ?, ?, ?)""")
                .params(e.transactionId(), e.merchant(), Cents.toCents(e.amount()), e.category(),
                        Cents.toText(e.timestamp()))
                .update(keys);
        return new ExpenseEntity(keys.getKey().longValue(), e.transactionId(), e.merchant(), e.amount(),
                e.category(), e.timestamp());
    }

    private static final String SELECT =
            "SELECT id, transaction_id, merchant, amount_cents, category, occurred_at FROM expense";

    public List<ExpenseEntity> findAll() {
        return jdbc.sql(SELECT + " ORDER BY id").query(ExpenseRepository::map).list();
    }

    public Optional<ExpenseEntity> findById(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(ExpenseRepository::map).optional();
    }

    public Optional<ExpenseEntity> findByTransactionId(String transactionId) {
        return jdbc.sql(SELECT + " WHERE transaction_id = ?").param(transactionId)
                .query(ExpenseRepository::map).optional();
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

    /** Total per category; exact because the sum is taken over integer cents. */
    public Map<String, BigDecimal> sumAmountByCategory() {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        jdbc.sql("SELECT category, SUM(amount_cents) AS total FROM expense GROUP BY category ORDER BY category")
                .query(rs -> {
                    totals.put(rs.getString("category"), Cents.money(rs, "total"));
                });
        return totals;
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM expense").update();
    }
}
