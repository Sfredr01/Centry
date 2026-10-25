package com.centry.notify;

import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetLevel;
import com.centry.repository.BudgetAlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Proves the sweeper really runs on its own: scheduling on, short interval, no notifier configured.
@SpringBootTest(properties = {"centry.notify.retry-enabled=true", "centry.notify.retry-interval=PT0.2S"})
@ActiveProfiles("test")
class AlertRetrySchedulingTest {

    @Autowired
    private BudgetAlertRepository alerts;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void scheduledSweepDeliversUnsentAlerts() {
        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);
        long id = alerts.insertIfAbsent(new BudgetAlertEntity(null, "scheduled", new BigDecimal("90.00"),
                new BigDecimal("100.00"), new BigDecimal("80.00"), BudgetLevel.WARNING_EXCEEDED, "msg", twoHoursAgo),
                YearMonth.from(twoHoursAgo)).getAsLong();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                jdbc.sql("SELECT notified_at FROM budget_alert WHERE id = ?").param(id)
                        .query(String.class).optional()).isPresent());
    }
}
