package com.centry.repository;

import com.centry.entity.SubscriptionAlertEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class SubscriptionAlertRepository {

    private final JdbcClient jdbc;

    public SubscriptionAlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SubscriptionAlertEntity save(SubscriptionAlertEntity a) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO subscription_alert (merchant, previous_amount_cents, current_amount_cents,
                                                        anomaly_type, message, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(a.merchant(), Cents.toCents(a.previousAmount()), Cents.toCents(a.currentAmount()),
                        a.anomalyType(), a.message(), Cents.toText(a.timestamp()))
                .update(keys);
        return new SubscriptionAlertEntity(keys.getKey().longValue(), a.merchant(), a.previousAmount(),
                a.currentAmount(), a.anomalyType(), a.message(), a.timestamp());
    }

    public List<SubscriptionAlertEntity> findTop50ByOrderByTimestampDesc() {
        return jdbc.sql("SELECT * FROM subscription_alert ORDER BY occurred_at DESC, id DESC LIMIT 50")
                .query(SubscriptionAlertRepository::map)
                .list();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM subscription_alert").update();
    }

    private static SubscriptionAlertEntity map(ResultSet rs, int row) throws SQLException {
        return new SubscriptionAlertEntity(
                rs.getLong("id"),
                rs.getString("merchant"),
                Cents.money(rs, "previous_amount_cents"),
                Cents.money(rs, "current_amount_cents"),
                rs.getString("anomaly_type"),
                rs.getString("message"),
                Cents.fromText(rs.getString("occurred_at")));
    }
}
