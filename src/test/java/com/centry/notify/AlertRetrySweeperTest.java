package com.centry.notify;

import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import com.centry.repository.BudgetAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// sweep() is called directly with a fixed clock; the scheduler itself is off in the test profile.
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AlertRetrySweeperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 2, 12, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-02T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private BudgetAlertRepository alerts;

    @Autowired
    private JdbcClient jdbc;

    private final FakeNotifier notifier = new FakeNotifier();
    private AlertRetrySweeper sweeper;
    private int counter;

    @BeforeEach
    void setUp() {
        alerts.deleteAll();
        notifier.reset();
        sweeper = new AlertRetrySweeper(alerts, new AlertDispatcher(FakeNotifier.providerOf(notifier), alerts, CLOCK),
                CLOCK);
    }

    private static BudgetAlertEntity entity(String category, LocalDateTime at) {
        return new BudgetAlertEntity(null, category, new BigDecimal("90.00"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), BudgetLevel.WARNING_EXCEEDED, "msg " + category, at);
    }

    /** An undelivered alert raised at the given time; each call is a distinct category, so none is deduped. */
    private long unsent(LocalDateTime at) {
        return alerts.insertIfAbsent(entity("cat" + counter++, at), YearMonth.from(at)).getAsLong();
    }

    private Optional<String> notifiedAt(long id) {
        return jdbc.sql("SELECT notified_at FROM budget_alert WHERE id = ?").param(id)
                .query(String.class).optional();
    }

    @Test
    void resendsUnsentWithin24h() {
        long id = unsent(NOW.minusHours(2));

        sweeper.sweep();

        assertThat(notifier.sent()).singleElement().satisfies(event -> {
            assertThat(event.getAlertId()).isEqualTo(id);
            assertThat(event.getCategory()).isEqualTo("cat0");
            assertThat(event.getMessage()).isEqualTo("msg cat0");
            assertThat(event.getAlertLevel()).isEqualTo(BudgetLevel.WARNING_EXCEEDED);
            assertThat(event.getCurrentSpend()).isEqualByComparingTo("90.00");
            assertThat(event.getMonthlyLimit()).isEqualByComparingTo("100.00");
            assertThat(event.getWarningThreshold()).isEqualByComparingTo("80.00");
        });
    }

    @Test
    void windowBoundariesAreInclusive() {
        unsent(NOW.minusHours(24));
        unsent(NOW.minusMinutes(1));

        sweeper.sweep();

        assertThat(notifier.sent()).extracting(BudgetAlertEvent::getCategory).containsExactlyInAnyOrder("cat0", "cat1");
    }

    @Test
    void skipsAlertsOlderThan24h(CapturedOutput output) {
        long id = unsent(NOW.minusHours(24).minusSeconds(1));

        sweeper.sweep();
        sweeper.sweep();

        assertThat(notifier.sent()).isEmpty();
        assertThat(notifiedAt(id)).isEmpty();
        assertThat(output.getAll().split("Abandoning alert " + id, -1)).hasSize(2); // logged exactly once
    }

    @Test
    void skipsAlertsInsideGraceWindow() {
        unsent(NOW.minusSeconds(59));

        sweeper.sweep();

        assertThat(notifier.sent()).isEmpty();
    }

    @Test
    void skipsLegacyRowsWithoutPeriod() {
        alerts.save(entity("legacy", NOW.minusHours(2)));

        sweeper.sweep();

        assertThat(notifier.sent()).isEmpty();
    }

    @Test
    void marksNotifiedAfterSuccessfulRetry() {
        long id = unsent(NOW.minusHours(2));
        notifier.failing(true);

        sweeper.sweep();
        assertThat(notifier.sent()).hasSize(1);
        assertThat(notifiedAt(id)).isEmpty();

        notifier.failing(false);
        sweeper.sweep();
        assertThat(notifier.sent()).hasSize(2);
        assertThat(notifiedAt(id)).contains("2026-03-02T12:00:00");

        sweeper.sweep();
        assertThat(notifier.sent()).hasSize(2); // delivered alerts are not retried
    }
}
