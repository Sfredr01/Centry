package com.centry.service;

import com.centry.CentryApplication;
import com.centry.dto.ExpenseEvent;
import com.centry.entity.SubscriptionAlertEntity;
import com.centry.model.SubscriptionAlertEvent;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.SubscriptionAlertRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// The "previous charge" of a merchant is read from the expense table, never from process memory.
class SubscriptionCheckTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 3, 10, 12, 0);

    private static ExpenseEvent charge(String merchant, String amount) {
        return ExpenseEvent.builder().merchant(merchant).amount(new BigDecimal(amount))
                .category("subscriptions").timestamp(AT).build();
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @RecordApplicationEvents
    class AgainstSharedDatabase {

        @Autowired
        private ExpenseService service;

        @Autowired
        private ExpenseRepository expenses;

        @Autowired
        private SubscriptionAlertRepository alerts;

        @Autowired
        private ApplicationEvents applicationEvents;

        private List<SubscriptionAlertEvent> subscriptionAlerts() {
            return applicationEvents.stream(SubscriptionAlertEvent.class).toList();
        }

        private void clean() {
            expenses.deleteAll();
            alerts.deleteAll();
        }

        @Test
        void previousChargeComesFromDatabase() {
            clean();
            // Stored behind the service's back: a map filled only by record() could not know about it.
            expenses.save(new com.centry.entity.ExpenseEntity(null, "seed-1", "Netflix", new BigDecimal("15.49"),
                    "subscriptions", AT.minusDays(30), null, null));

            service.record(charge("  NETFLIX ", "17.99"));

            assertThat(subscriptionAlerts()).singleElement().satisfies(alert -> {
                assertThat(alert.getPreviousAmount()).isEqualByComparingTo("15.49");
                assertThat(alert.getCurrentAmount()).isEqualByComparingTo("17.99");
            });
        }

        @Test
        void previousIsTheLatestChargeOfThatMerchantOnly() {
            clean();
            service.record(charge("Netflix", "10.00"));
            service.record(charge("Hulu", "50.00"));
            service.record(charge("Netflix", "20.00"));
            // Compared with the latest Netflix charge (20.00), not the first (10.00) or Hulu's.
            service.record(charge("Netflix", "15.00"));

            assertThat(subscriptionAlerts()).singleElement()
                    .satisfies(alert -> assertThat(alert.getPreviousAmount()).isEqualByComparingTo("10.00"));
            assertThat(alerts.findTop50ByOrderByTimestampDesc()).hasSize(1);
        }

        @Test
        void priceIncreaseAlertsUnchanged() {
            clean();
            service.record(charge("Netflix", "15.49"));
            assertThat(subscriptionAlerts()).isEmpty();

            service.record(charge("  NETFLIX ", "17.99"));

            SubscriptionAlertEvent alert = subscriptionAlerts().get(0);
            assertThat(alert.getAnomalyType()).isEqualTo("PRICE_INCREASE");
            assertThat(alert.getMerchant()).isEqualTo("  NETFLIX ");
            assertThat(alert.getTimestamp()).isEqualTo(AT);
            assertThat(alert.getMessage()).isEqualTo(
                    "Price increase detected for subscription [  NETFLIX ]: jumped from $15.49 to $17.99 (+$2.50)");
            assertThat(alerts.findTop50ByOrderByTimestampDesc()).singleElement().satisfies(row -> {
                assertThat(row.merchant()).isEqualTo("  NETFLIX ");
                assertThat(row.previousAmount()).isEqualByComparingTo("15.49");
                assertThat(row.currentAmount()).isEqualByComparingTo("17.99");
                assertThat(row.anomalyType()).isEqualTo("PRICE_INCREASE");
                assertThat(row.message()).isEqualTo(alert.getMessage());
            });
        }

        @Test
        void equalOrLowerChargeAlertsNothing() {
            clean();
            service.record(charge("Netflix", "15.49"));
            service.record(charge("Netflix", "15.49"));
            service.record(charge("Netflix", "9.99"));

            assertThat(subscriptionAlerts()).isEmpty();
            assertThat(alerts.findTop50ByOrderByTimestampDesc()).isEmpty();
        }
    }

    @TempDir
    Path dir;

    private ConfigurableApplicationContext start() {
        return new SpringApplication(CentryApplication.class).run(
                "--spring.profiles.active=test",
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=jdbc:sqlite:" + dir.resolve("data").resolve("centry.db"));
    }

    @Test
    void survivesRestart() {
        try (ConfigurableApplicationContext first = start()) {
            first.getBean(ExpenseService.class).record(charge("Netflix", "9.99"));
        }

        try (ConfigurableApplicationContext second = start()) {
            second.getBean(ExpenseService.class).record(charge("Netflix", "12.99"));

            List<SubscriptionAlertEntity> alerts =
                    second.getBean(SubscriptionAlertRepository.class).findTop50ByOrderByTimestampDesc();
            assertThat(alerts).singleElement().satisfies(alert -> {
                assertThat(alert.previousAmount()).isEqualByComparingTo("9.99");
                assertThat(alert.currentAmount()).isEqualByComparingTo("12.99");
            });
        }
    }
}
