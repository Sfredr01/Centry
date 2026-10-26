package com.centry.repository;

import com.centry.model.Subscription;
import com.centry.model.SubscriptionCadence;
import com.centry.model.SubscriptionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SubscriptionRepository {

    private final JdbcClient jdbc;

    public SubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A manual add: creates the subscription, or takes over the row of the same merchant key whatever its status
     * (a candidate or an ignored one). A person said so, so it is confirmed and reviewed today.
     */
    public void upsertConfirmed(String merchantKey, String name, SubscriptionCadence cadence, BigDecimal expected,
            LocalDate nextDueOn, LocalDate today, LocalDateTime now) {
        jdbc.sql("""
                        INSERT INTO subscription (merchant_key, name, cadence, expected_cents, next_due_on, status,
                                                  reviewed_on, created_at)
                        VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?)
                        ON CONFLICT (merchant_key) DO UPDATE SET
                            name = excluded.name,
                            cadence = excluded.cadence,
                            expected_cents = excluded.expected_cents,
                            next_due_on = excluded.next_due_on,
                            status = 'CONFIRMED',
                            reviewed_on = excluded.reviewed_on""")
                .params(merchantKey, name, cadence.name(), Cents.toCents(expected), nextDueOn.toString(),
                        today.toString(), Cents.toText(now))
                .update();
    }

    /** Edits the person-owned fields and confirms. @return false if there is no such subscription */
    public boolean update(long id, String name, SubscriptionCadence cadence, BigDecimal expected,
            LocalDate nextDueOn, LocalDate today) {
        return jdbc.sql("""
                        UPDATE subscription SET name = ?, cadence = ?, expected_cents = ?, next_due_on = ?,
                                                status = 'CONFIRMED', reviewed_on = ?
                        WHERE id = ?""")
                .params(name, cadence.name(), Cents.toCents(expected), nextDueOn.toString(), today.toString(), id)
                .update() == 1;
    }

    public boolean confirm(long id, LocalDate today) {
        return review(id, SubscriptionStatus.CONFIRMED, today);
    }

    public boolean ignore(long id, LocalDate today) {
        return review(id, SubscriptionStatus.IGNORED, today);
    }

    /** @return true if a row existed and was removed */
    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM subscription WHERE id = ?").param(id).update() == 1;
    }

    public Optional<Subscription> findById(long id) {
        return jdbc.sql("SELECT * FROM subscription WHERE id = ?").param(id)
                .query(SubscriptionRepository::map).optional();
    }

    public List<Subscription> findByStatus(SubscriptionStatus status) {
        return jdbc.sql("SELECT * FROM subscription WHERE status = ? ORDER BY lower(name), id")
                .param(status.name()).query(SubscriptionRepository::map).list();
    }

    /** Yearly cost of the confirmed subscriptions: monthly ones times twelve, yearly ones as they are. */
    public BigDecimal annualTotal() {
        long cents = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE cadence WHEN 'MONTHLY' THEN expected_cents * 12
                                                         ELSE expected_cents END), 0)
                        FROM subscription WHERE status = 'CONFIRMED'""")
                .query(Long.class).single();
        return BigDecimal.valueOf(cents, 2);
    }

    private boolean review(long id, SubscriptionStatus status, LocalDate today) {
        return jdbc.sql("UPDATE subscription SET status = ?, reviewed_on = ? WHERE id = ?")
                .params(status.name(), today.toString(), id).update() == 1;
    }

    private static Subscription map(ResultSet rs, int row) throws SQLException {
        String reviewed = rs.getString("reviewed_on");
        return new Subscription(rs.getLong("id"), rs.getString("merchant_key"), rs.getString("name"),
                SubscriptionCadence.valueOf(rs.getString("cadence")), Cents.money(rs, "expected_cents"),
                LocalDate.parse(rs.getString("next_due_on")), SubscriptionStatus.valueOf(rs.getString("status")),
                reviewed == null ? null : LocalDate.parse(reviewed), Cents.fromText(rs.getString("created_at")));
    }
}
