package com.centry.notify;

import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import com.centry.repository.BudgetAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Real transactions and a real SQLite file; the outbound channels are fakes so delivery is observable.
@SpringBootTest
@ActiveProfiles("test")
@Import(AlertDispatcherTest.Fakes.class)
class AlertDispatcherTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 3, 1, 12, 0);

    @TestConfiguration
    static class Fakes {
        @Bean
        FakeNotifier fakeA() {
            return new FakeNotifier();
        }

        @Bean
        FakeNotifier fakeB() {
            return new FakeNotifier();
        }
    }

    @Autowired
    private FakeNotifier fakeA;

    @Autowired
    private FakeNotifier fakeB;

    @Autowired
    private BudgetAlertRepository alerts;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        fakeA.reset();
        fakeB.reset();
    }

    private long insertAlert() {
        return alerts.insertIfAbsent(new BudgetAlertEntity(null, "food", new BigDecimal("90.00"),
                new BigDecimal("100.00"), new BigDecimal("80.00"), BudgetLevel.WARNING_EXCEEDED, "msg", AT),
                YearMonth.of(2026, 3)).getAsLong();
    }

    private static BudgetAlertEvent event(long id) {
        BudgetAlertEvent event = new BudgetAlertEvent("food", new BigDecimal("90.00"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), BudgetLevel.WARNING_EXCEEDED, "msg");
        event.setAlertId(id);
        return event;
    }

    /** Stores an alert and publishes its event inside one transaction, like ExpenseService does. */
    private long raiseAlert(boolean commit) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            long id = insertAlert();
            events.publishEvent(event(id));
            if (!commit) {
                status.setRollbackOnly();
            }
            return id;
        });
    }

    private Optional<String> notifiedAt(long id) {
        return jdbc.sql("SELECT notified_at FROM budget_alert WHERE id = ?").param(id)
                .query(String.class).optional();
    }

    @Test
    void sendsToAllConfiguredNotifiersAfterCommitAndMarksNotified() {
        long id = raiseAlert(true);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(notifiedAt(id)).isPresent());
        assertThat(fakeA.sent()).singleElement().extracting(BudgetAlertEvent::getAlertId).isEqualTo(id);
        assertThat(fakeB.sent()).hasSize(1);
    }

    @Test
    void rollbackSendsNothing() {
        raiseAlert(false);

        await().during(Duration.ofMillis(750)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(fakeA.sent()).isEmpty();
            assertThat(fakeB.sent()).isEmpty();
        });
        assertThat(alerts.findTop50ByOrderByTimestampDesc()).isEmpty();
    }

    @Test
    void failureLeavesNotifiedAtNull() {
        fakeA.failing(true);

        long id = raiseAlert(true);

        // The healthy notifier is still tried, but one failure keeps the alert undelivered.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(fakeA.sent()).hasSize(1);
            assertThat(fakeB.sent()).hasSize(1);
        });
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(notifiedAt(id).orElse(null)).isNull());
    }

    @Test
    void noNotifiersConfiguredMarksNotified() {
        Clock fixed = Clock.fixed(Instant.parse("2026-03-01T13:00:00Z"), ZoneOffset.UTC);
        long id = insertAlert();

        boolean delivered = new AlertDispatcher(FakeNotifier.providerOf(), alerts, fixed).dispatch(event(id));

        assertThat(delivered).isTrue();
        assertThat(notifiedAt(id)).contains("2026-03-01T13:00:00");
    }
}
