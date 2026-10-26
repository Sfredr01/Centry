package com.centry.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Delivery state of monthly digests, keyed by the 'YYYY-MM' period they summarise. */
@Repository
public class DigestRepository {

    private final JdbcClient jdbc;

    public DigestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when this call created the row, which makes the caller the one that sends the digest */
    public boolean insertIfAbsent(String period, LocalDateTime createdAt) {
        return jdbc.sql("INSERT INTO digest (period, created_at) VALUES (?, ?) ON CONFLICT (period) DO NOTHING")
                .params(period, Cents.toText(createdAt))
                .update() == 1;
    }

    public void markNotified(String period, LocalDateTime notifiedAt) {
        jdbc.sql("UPDATE digest SET notified_at = ? WHERE period = ?")
                .params(Cents.toText(notifiedAt), period)
                .update();
    }

    /** Undelivered digests created within [from, to] (both inclusive), oldest first. */
    public List<String> findUnsent(LocalDateTime from, LocalDateTime to) {
        return jdbc.sql("""
                        SELECT period FROM digest
                         WHERE notified_at IS NULL AND created_at >= ? AND created_at <= ?
                         ORDER BY created_at, period""")
                .params(Cents.toText(from), Cents.toText(to))
                .query(String.class)
                .list();
    }

    /** Undelivered digests created before the given time. */
    public List<String> findAbandoned(LocalDateTime before) {
        return jdbc.sql("SELECT period FROM digest WHERE notified_at IS NULL AND created_at < ?")
                .param(Cents.toText(before))
                .query(String.class)
                .list();
    }
}
