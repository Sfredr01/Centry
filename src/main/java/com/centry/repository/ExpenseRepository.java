package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public List<ExpenseEntity> findAll() {
        return jdbc.sql("SELECT id, transaction_id, merchant, amount_cents, category, occurred_at "
                        + "FROM expense ORDER BY id")
                .query((rs, n) -> new ExpenseEntity(
                        rs.getLong("id"),
                        rs.getString("transaction_id"),
                        rs.getString("merchant"),
                        Cents.money(rs, "amount_cents"),
                        rs.getString("category"),
                        Cents.fromText(rs.getString("occurred_at"))))
                .list();
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
