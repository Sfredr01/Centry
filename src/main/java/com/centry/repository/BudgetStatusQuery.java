package com.centry.repository;

import com.centry.model.BudgetStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.util.Optional;

/** Budget status is computed from the expense table on every call; nothing is cached. */
@Repository
public class BudgetStatusQuery {

    private final JdbcClient jdbc;

    public BudgetStatusQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when the category has no budget: such a category has no status and never alerts. */
    public Optional<BudgetStatus> status(String categoryKey, YearMonth month) {
        // Two statements, not a nested query in the row mapper: the pool has a single connection.
        return jdbc.sql("SELECT monthly_limit_cents, warn_pct FROM budget WHERE category_key = ?")
                .param(categoryKey)
                .query((rs, row) -> new Limits(rs.getLong("monthly_limit_cents"), rs.getInt("warn_pct")))
                .optional()
                .map(limits -> BudgetStatus.of(categoryKey, limits.limitCents(), limits.warnPercent(),
                        spentCents(categoryKey, month)));
    }

    private record Limits(long limitCents, int warnPercent) {
    }

    // Spend of [month start, next month start): ISO-8601 text sorts chronologically, and the bounds are
    // formatted exactly like stored values. The key is a bound parameter, not a joined column, because SQLite
    // only picks idx_expense_category_key_occurred_at for lower(trim(category)) = <constant>.
    private long spentCents(String categoryKey, YearMonth month) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount_cents), 0) FROM expense
                         WHERE lower(trim(category)) = ? AND occurred_at >= ? AND occurred_at < ?""")
                .params(categoryKey, Cents.toText(month.atDay(1).atStartOfDay()),
                        Cents.toText(month.plusMonths(1).atDay(1).atStartOfDay()))
                .query(Long.class)
                .single();
    }
}
