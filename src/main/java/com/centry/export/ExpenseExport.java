package com.centry.export;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Writes the expenses of a date range as CSV, one database row at a time. */
@Component
class ExpenseExport {

    // The schema has no source column; rows with a creator were entered in the app, the rest arrived by feed.
    // occurred_at is ISO text, so the half-open text bounds are the same ones the budget queries use.
    private static final String QUERY = """
            SELECT substr(e.occurred_at, 1, 10) AS day, e.merchant, e.category, e.amount_cents,
                   s.display_name AS spent_by, c.display_name AS created_by,
                   CASE WHEN e.created_by IS NULL THEN 'feed' ELSE 'app' END AS source, e.transaction_id
              FROM expense e
              LEFT JOIN users s ON s.id = e.spent_by
              LEFT JOIN users c ON c.id = e.created_by
             WHERE e.occurred_at >= ? AND e.occurred_at < ?
             ORDER BY day, e.id""";

    private final JdbcClient jdbc;

    ExpenseExport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Both dates are inclusive. */
    void write(LocalDate from, LocalDate to, Writer out) throws IOException {
        CsvWriter csv = new CsvWriter(out);
        csv.row("date", "merchant", "category", "amount", "spent_by", "created_by", "source", "transaction_id");
        try {
            jdbc.sql(QUERY).params(from + "T00:00:00", to.plusDays(1) + "T00:00:00").query(rs -> {
                try {
                    csv.row(rs.getString("day"), rs.getString("merchant"), rs.getString("category"),
                            BigDecimal.valueOf(rs.getLong("amount_cents"), 2).toPlainString(),
                            rs.getString("spent_by"), rs.getString("created_by"), rs.getString("source"),
                            rs.getString("transaction_id"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
