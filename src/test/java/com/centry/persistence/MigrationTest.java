package com.centry.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class MigrationTest {

    private static final String INSERT =
            "INSERT INTO expense (transaction_id, amount_cents, occurred_at) VALUES (?, ?, '2026-01-01T00:00:00')";

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM expense").update();
    }

    @Test
    void v1AppliesOnEmptyDb() {
        assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '1'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table' AND name IN "
                        + "('expense', 'budget_alert', 'subscription_alert')")
                .query(String.class).list()).containsExactlyInAnyOrder("expense", "budget_alert", "subscription_alert");
        assertThat(jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'expense' "
                        + "AND sql LIKE '%(category, occurred_at)%'")
                .query(String.class).list()).hasSize(1);
        assertThat(jdbc.sql("SELECT strict FROM pragma_table_list WHERE name = 'expense'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> jdbc.sql(INSERT).params("tx-zero", 0).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CHECK constraint failed");
        assertThatThrownBy(() -> jdbc.sql(INSERT).params("tx-neg", -5).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CHECK constraint failed");
    }

    @Test
    void rejectsDuplicateTransactionId() {
        jdbc.sql(INSERT).params("tx-dup", 100).update();

        assertThatThrownBy(() -> jdbc.sql(INSERT).params("tx-dup", 200).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UNIQUE constraint failed");
    }
}
