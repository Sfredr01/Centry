package com.centry.repository;

import com.centry.model.Subscription;
import com.centry.model.SubscriptionCadence;
import com.centry.model.SubscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SubscriptionRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 10, 0);

    @Autowired
    private SubscriptionRepository subscriptions;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM subscription").update();
    }

    private void insert(String key, String cadence, int cents, String status) {
        jdbc.sql("INSERT INTO subscription (merchant_key, name, cadence, expected_cents, next_due_on, status, "
                        + "reviewed_on, created_at) VALUES (?, ?, ?, ?, '2026-04-04', ?, ?, '2026-03-01T00:00:00')")
                .params(key, key.toUpperCase(), cadence, cents, status,
                        status.equals("CANDIDATE") ? null : "2026-03-02").update();
    }

    @Test
    void manualUpsertConfirmsExistingCandidate() {
        insert("netflix", "MONTHLY", 1549, "CANDIDATE");
        long candidateId = subscriptions.findByStatus(SubscriptionStatus.CANDIDATE).getFirst().id();

        subscriptions.upsertConfirmed("netflix", "Netflix", SubscriptionCadence.YEARLY, new BigDecimal("180.00"),
                LocalDate.of(2027, 1, 3), TODAY, NOW);

        Subscription s = subscriptions.findById(candidateId).orElseThrow();
        assertThat(s.status()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(s.name()).isEqualTo("Netflix");
        assertThat(s.cadence()).isEqualTo(SubscriptionCadence.YEARLY);
        assertThat(s.expected()).isEqualByComparingTo("180.00");
        assertThat(s.nextDueOn()).isEqualTo(LocalDate.of(2027, 1, 3));
        assertThat(s.reviewedOn()).isEqualTo(TODAY);
        assertThat(s.createdAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(subscriptions.findByStatus(SubscriptionStatus.CONFIRMED)).hasSize(1);
    }

    @Test
    void manualUpsertCreatesNewConfirmedRow() {
        subscriptions.upsertConfirmed("hulu", "Hulu", SubscriptionCadence.MONTHLY, new BigDecimal("7.99"),
                LocalDate.of(2026, 4, 1), TODAY, NOW);

        Subscription s = subscriptions.findByStatus(SubscriptionStatus.CONFIRMED).getFirst();
        assertThat(s.merchantKey()).isEqualTo("hulu");
        assertThat(s.expected()).isEqualByComparingTo("7.99");
        assertThat(s.createdAt()).isEqualTo(NOW);
    }

    @Test
    void annualTotalCountsConfirmedOnly() {
        assertThat(subscriptions.annualTotal()).isEqualByComparingTo("0");
        insert("netflix", "MONTHLY", 1549, "CONFIRMED");
        insert("prime", "YEARLY", 13900, "CONFIRMED");
        insert("maybe", "MONTHLY", 99999, "CANDIDATE");
        insert("dropped", "MONTHLY", 99999, "IGNORED");

        // 15.49 * 12 + 139.00
        assertThat(subscriptions.annualTotal()).isEqualByComparingTo("324.88");
    }

    @Test
    void updateConfirmIgnoreAndDeleteReportWhetherARowChanged() {
        insert("netflix", "MONTHLY", 1549, "CANDIDATE");
        long id = subscriptions.findByStatus(SubscriptionStatus.CANDIDATE).getFirst().id();

        assertThat(subscriptions.update(id, "Netflix HD", SubscriptionCadence.YEARLY, new BigDecimal("99.00"),
                LocalDate.of(2027, 1, 1), TODAY)).isTrue();
        Subscription edited = subscriptions.findById(id).orElseThrow();
        assertThat(edited.status()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(edited.reviewedOn()).isEqualTo(TODAY);
        assertThat(edited.merchantKey()).isEqualTo("netflix");

        assertThat(subscriptions.ignore(id, TODAY.plusDays(1))).isTrue();
        assertThat(subscriptions.findById(id).orElseThrow().status()).isEqualTo(SubscriptionStatus.IGNORED);
        assertThat(subscriptions.confirm(id, TODAY.plusDays(2))).isTrue();
        Subscription confirmed = subscriptions.findById(id).orElseThrow();
        assertThat(confirmed.status()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(confirmed.reviewedOn()).isEqualTo(TODAY.plusDays(2));

        assertThat(subscriptions.delete(id)).isTrue();
        assertThat(subscriptions.delete(id)).isFalse();
        assertThat(subscriptions.update(id, "x", SubscriptionCadence.MONTHLY, BigDecimal.ONE, TODAY, TODAY)).isFalse();
        assertThat(subscriptions.confirm(id, TODAY)).isFalse();
        assertThat(subscriptions.ignore(id, TODAY)).isFalse();
        assertThat(subscriptions.findById(id)).isEmpty();
    }

    @Test
    void findByStatusSortsByName() {
        insert("b", "MONTHLY", 100, "CONFIRMED");
        insert("a", "MONTHLY", 100, "CONFIRMED");

        assertThat(subscriptions.findByStatus(SubscriptionStatus.CONFIRMED)).extracting(Subscription::merchantKey)
                .containsExactly("a", "b");
    }
}
