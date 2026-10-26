package com.centry.repository;

import com.centry.model.BudgetStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
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
        // Limits first, spend second: the pool has a single connection, so no query inside a row mapper.
        return jdbc.sql(SELECT_LIMITS + " WHERE category_key = ?").param(categoryKey).query(Limits::map)
                .optional()
                .map(limits -> limits.toStatus(spentCents(limits.categoryKey(), month, null)));
    }

    /** Every budget with its status for the month, sorted by category key. */
    public List<BudgetStatus> all(YearMonth month) {
        return all(month, null);
    }

    /**
     * Like {@link #all(YearMonth)}, but spent counts only what {@code spentBy} spent when it is not null. The
     * limit stays the household's.
     */
    public List<BudgetStatus> all(YearMonth month, Long spentBy) {
        return jdbc.sql(SELECT_LIMITS + " ORDER BY category_key").query(Limits::map).list().stream()
                .map(limits -> limits.toStatus(spentCents(limits.categoryKey(), month, spentBy)))
                .toList();
    }

    private static final String SELECT_LIMITS = "SELECT category_key, monthly_limit_cents, warn_pct FROM budget";

    private record Limits(String categoryKey, long limitCents, int warnPercent) {

        static Limits map(ResultSet rs, int row) throws SQLException {
            return new Limits(rs.getString("category_key"), rs.getLong("monthly_limit_cents"), rs.getInt("warn_pct"));
        }

        BudgetStatus toStatus(long spentCents) {
            return BudgetStatus.of(categoryKey, limitCents, warnPercent, spentCents);
        }
    }

    // Spend of [month start, next month start): ISO-8601 text sorts chronologically, and the bounds are
    // formatted exactly like stored values. The key is a bound parameter, not a joined column, because SQLite
    // only picks idx_expense_category_key_occurred_at for lower(trim(category)) = <constant>.
    private long spentCents(String categoryKey, YearMonth month, Long spentBy) {
        String sql = """
                SELECT COALESCE(SUM(amount_cents), 0) FROM expense
                 WHERE lower(trim(category)) = ? AND occurred_at >= ? AND occurred_at < ?""";
        List<Object> params = new ArrayList<>(List.of(categoryKey, Cents.monthStart(month), Cents.monthEnd(month)));
        if (spentBy != null) {
            sql += " AND spent_by = ?";
            params.add(spentBy);
        }
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
