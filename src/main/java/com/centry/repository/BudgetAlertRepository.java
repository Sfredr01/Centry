package com.centry.repository;

import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetLevel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.OptionalLong;

@Repository
public class BudgetAlertRepository {

    private final JdbcClient jdbc;

    public BudgetAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BudgetAlertEntity save(BudgetAlertEntity a) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO budget_alert (category, current_spend_cents, monthly_limit_cents,
                                                  warning_amount_cents, status, message, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(a.category(), Cents.toCents(a.currentSpend()), Cents.toCents(a.monthlyLimit()),
                        Cents.toCents(a.warningAmount()), a.status() == null ? null : a.status().name(),
                        a.message(), Cents.toText(a.timestamp()))
                .update(keys);
        return new BudgetAlertEntity(keys.getKey().longValue(), a.category(), a.currentSpend(), a.monthlyLimit(),
                a.warningAmount(), a.status(), a.message(), a.timestamp());
    }

    /**
     * Stores the alert unless one exists for the same category (normalized), month and level.
     *
     * @return the new row's id, or empty if the dedupe index ignored the insert
     */
    public OptionalLong insertIfAbsent(BudgetAlertEntity a, YearMonth period) {
        var keys = new GeneratedKeyHolder();
        int inserted = jdbc.sql("""
                        INSERT OR IGNORE INTO budget_alert (category, current_spend_cents, monthly_limit_cents,
                                                            warning_amount_cents, status, message, occurred_at, period)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(a.category(), Cents.toCents(a.currentSpend()), Cents.toCents(a.monthlyLimit()),
                        Cents.toCents(a.warningAmount()), a.status().name(), a.message(),
                        Cents.toText(a.timestamp()), period.toString())
                .update(keys);
        // After an ignored insert the driver would hand back the previous row's key, so only read it on success.
        return inserted == 1 ? OptionalLong.of(keys.getKey().longValue()) : OptionalLong.empty();
    }

    public List<BudgetAlertEntity> findTop50ByOrderByTimestampDesc() {
        return jdbc.sql("SELECT * FROM budget_alert ORDER BY occurred_at DESC, id DESC LIMIT 50")
                .query(BudgetAlertRepository::map)
                .list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM budget_alert").update();
    }

    private static BudgetAlertEntity map(ResultSet rs, int row) throws SQLException {
        String status = rs.getString("status");
        return new BudgetAlertEntity(
                rs.getLong("id"),
                rs.getString("category"),
                Cents.money(rs, "current_spend_cents"),
                Cents.money(rs, "monthly_limit_cents"),
                Cents.money(rs, "warning_amount_cents"),
                status == null ? null : BudgetLevel.valueOf(status),
                rs.getString("message"),
                Cents.fromText(rs.getString("occurred_at")));
    }
}
