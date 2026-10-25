package com.centry.repository;

import com.centry.entity.BudgetAlertEntity;
import com.centry.entity.SubscriptionAlertEntity;
import com.centry.model.BudgetLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AlertRepositoriesTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private BudgetAlertRepository budgetRepository;

    @Autowired
    private SubscriptionAlertRepository subscriptionRepository;

    @BeforeEach
    void clean() {
        budgetRepository.deleteAll();
        subscriptionRepository.deleteAll();
    }

    @Test
    void budgetAlertsOrderedNewestFirst() {
        budgetRepository.save(budget("a", BASE.plusHours(1)));
        budgetRepository.save(budget("c", BASE.plusHours(3)));
        budgetRepository.save(budget("b", BASE.plusHours(2)));

        assertThat(budgetRepository.findTop50ByOrderByTimestampDesc())
                .extracting(BudgetAlertEntity::category)
                .containsExactly("c", "b", "a");
    }

    @Test
    void budgetAlertsCappedAt50KeepingNewest() {
        for (int i = 0; i < 55; i++) {
            budgetRepository.save(budget("c" + i, BASE.plusMinutes(i)));
        }

        var result = budgetRepository.findTop50ByOrderByTimestampDesc();
        assertThat(result).hasSize(50);
        assertThat(result.get(0).category()).isEqualTo("c54");
        assertThat(result.get(49).category()).isEqualTo("c5");
    }

    @Test
    void budgetAlertRoundTripsAllFieldsAndNulls() {
        BudgetAlertEntity full = new BudgetAlertEntity(null, "dining", new BigDecimal("105.05"),
                new BigDecimal("100.00"), new BigDecimal("80.00"), BudgetLevel.LIMIT_EXCEEDED, "over", BASE);
        BudgetAlertEntity saved = budgetRepository.save(full);

        assertThat(saved.id()).isNotNull();
        assertThat(budgetRepository.findTop50ByOrderByTimestampDesc()).containsExactly(saved);

        budgetRepository.deleteAll();
        BudgetAlertEntity sparse = budgetRepository.save(budget("x", BASE));
        assertThat(budgetRepository.findTop50ByOrderByTimestampDesc()).containsExactly(sparse);
        assertThat(sparse.currentSpend()).isNull();
        assertThat(sparse.status()).isNull();
    }

    @Test
    void subscriptionAlertsOrderedNewestFirst() {
        subscriptionRepository.save(subscription("a", BASE.plusHours(1)));
        subscriptionRepository.save(subscription("c", BASE.plusHours(3)));
        subscriptionRepository.save(subscription("b", BASE.plusHours(2)));

        assertThat(subscriptionRepository.findTop50ByOrderByTimestampDesc())
                .extracting(SubscriptionAlertEntity::merchant)
                .containsExactly("c", "b", "a");
    }

    @Test
    void subscriptionAlertsCappedAt50KeepingNewest() {
        for (int i = 0; i < 55; i++) {
            subscriptionRepository.save(subscription("m" + i, BASE.plusMinutes(i)));
        }

        var result = subscriptionRepository.findTop50ByOrderByTimestampDesc();
        assertThat(result).hasSize(50);
        assertThat(result.get(0).merchant()).isEqualTo("m54");
        assertThat(result.get(49).merchant()).isEqualTo("m5");
    }

    @Test
    void subscriptionAlertRoundTripsAllFields() {
        SubscriptionAlertEntity saved = subscriptionRepository.save(new SubscriptionAlertEntity(null, "Netflix",
                new BigDecimal("15.49"), new BigDecimal("17.99"), "PRICE_INCREASE", "up", BASE));

        assertThat(saved.id()).isNotNull();
        assertThat(subscriptionRepository.findTop50ByOrderByTimestampDesc()).containsExactly(saved);
    }

    private BudgetAlertEntity budget(String category, LocalDateTime ts) {
        return new BudgetAlertEntity(null, category, null, null, null, null, null, ts);
    }

    private SubscriptionAlertEntity subscription(String merchant, LocalDateTime ts) {
        return new SubscriptionAlertEntity(null, merchant, null, null, null, null, ts);
    }
}
