package com.centry.repository;

import com.centry.entity.BudgetAlertEntity;
import com.centry.entity.SubscriptionAlertEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class AlertRepositoriesTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private BudgetAlertRepository budgetRepository;

    @Autowired
    private SubscriptionAlertRepository subscriptionRepository;

    @Test
    void budgetAlertsOrderedNewestFirst() {
        budgetRepository.saveAll(List.of(
                budget("a", BASE.plusHours(1)), budget("c", BASE.plusHours(3)), budget("b", BASE.plusHours(2))));

        assertThat(budgetRepository.findTop50ByOrderByTimestampDesc())
                .extracting(BudgetAlertEntity::getCategory)
                .containsExactly("c", "b", "a");
    }

    @Test
    void budgetAlertsCappedAt50KeepingNewest() {
        List<BudgetAlertEntity> all = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            all.add(budget("c" + i, BASE.plusMinutes(i)));
        }
        budgetRepository.saveAll(all);

        List<BudgetAlertEntity> result = budgetRepository.findTop50ByOrderByTimestampDesc();
        assertThat(result).hasSize(50);
        assertThat(result.get(0).getCategory()).isEqualTo("c54");
        assertThat(result.get(49).getCategory()).isEqualTo("c5");
    }

    @Test
    void subscriptionAlertsOrderedNewestFirst() {
        subscriptionRepository.saveAll(List.of(
                subscription("a", BASE.plusHours(1)), subscription("c", BASE.plusHours(3)),
                subscription("b", BASE.plusHours(2))));

        assertThat(subscriptionRepository.findTop50ByOrderByTimestampDesc())
                .extracting(SubscriptionAlertEntity::getMerchant)
                .containsExactly("c", "b", "a");
    }

    @Test
    void subscriptionAlertsCappedAt50KeepingNewest() {
        List<SubscriptionAlertEntity> all = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            all.add(subscription("m" + i, BASE.plusMinutes(i)));
        }
        subscriptionRepository.saveAll(all);

        List<SubscriptionAlertEntity> result = subscriptionRepository.findTop50ByOrderByTimestampDesc();
        assertThat(result).hasSize(50);
        assertThat(result.get(0).getMerchant()).isEqualTo("m54");
        assertThat(result.get(49).getMerchant()).isEqualTo("m5");
    }

    private BudgetAlertEntity budget(String category, LocalDateTime ts) {
        return BudgetAlertEntity.builder().category(category).timestamp(ts).build();
    }

    private SubscriptionAlertEntity subscription(String merchant, LocalDateTime ts) {
        return SubscriptionAlertEntity.builder().merchant(merchant).timestamp(ts).build();
    }
}
