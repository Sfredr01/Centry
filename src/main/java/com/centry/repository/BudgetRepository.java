package com.centry.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class BudgetRepository {

    private final JdbcClient jdbc;

    public BudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates or replaces the budget of a normalized category key (see {@code Keys.of}). */
    public void upsert(String categoryKey, BigDecimal monthlyLimit, int warnPercent) {
        jdbc.sql("""
                        INSERT INTO budget (category_key, monthly_limit_cents, warn_pct) VALUES (?, ?, ?)
                        ON CONFLICT (category_key) DO UPDATE SET
                            monthly_limit_cents = excluded.monthly_limit_cents,
                            warn_pct = excluded.warn_pct""")
                .params(categoryKey, Cents.toCents(monthlyLimit), warnPercent)
                .update();
    }

    /** @return true if a budget existed and was removed */
    public boolean delete(String categoryKey) {
        return jdbc.sql("DELETE FROM budget WHERE category_key = ?").param(categoryKey).update() == 1;
    }
}
