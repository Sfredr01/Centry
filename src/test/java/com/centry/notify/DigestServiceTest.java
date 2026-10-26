package com.centry.notify;

import com.centry.entity.ExpenseEntity;
import com.centry.model.Role;
import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.BudgetRepository;
import com.centry.repository.DigestQuery;
import com.centry.repository.DigestRepository;
import com.centry.repository.ExpenseRepository;
import com.centry.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The clock is fixed and the service methods are called directly; the scheduler is off in the test profile.
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class DigestServiceTest {

    private static final String CRON = "0 0 8 1 * *";
    private static final LocalDateTime APRIL_1_8AM = LocalDateTime.of(2026, 4, 1, 8, 0);

    @Autowired
    private DigestQuery query;

    @Autowired
    private DigestRepository digests;

    @Autowired
    private BudgetAlertRepository alerts;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    private final FakeNotifier notifier = new FakeNotifier();

    @BeforeEach
    @AfterEach
    void clean() {
        notifier.reset();
        expenses.deleteAll();
        jdbc.sql("DELETE FROM digest").update();
        jdbc.sql("DELETE FROM budget").update();
        jdbc.sql("DELETE FROM users WHERE username LIKE 'ds-%'").update();
    }

    private static Clock at(LocalDateTime time) {
        return Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private DigestService service(LocalDateTime now, Notifier... notifiers) {
        Clock clock = at(now);
        return new DigestService(query, digests,
                new AlertDispatcher(FakeNotifier.providerOf(notifiers), alerts, clock), clock, CRON);
    }

    private DigestService service(LocalDateTime now) {
        return service(now, notifier);
    }

    private void spend(String merchant, String category, String amount, Long spentBy) {
        expenses.save(new ExpenseEntity(null, UUID.randomUUID().toString(), merchant, new BigDecimal(amount),
                category, LocalDateTime.of(2026, 3, 15, 12, 0), spentBy, null));
    }

    private Optional<String> notifiedAt(String period) {
        return jdbc.sql("SELECT notified_at FROM digest WHERE period = ?").param(period)
                .query(String.class).optional();
    }

    private int rows() {
        return jdbc.sql("SELECT count(*) FROM digest").query(Integer.class).single();
    }

    @Test
    void formatsPhoneSizedMessage() {
        long ann = users.insert("ds-ann", "Ann", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();
        budgets.upsert("groceries", new BigDecimal("600.00"), 80);
        budgets.upsert("dining", new BigDecimal("200.50"), 80);
        spend("Costco", "Groceries", "450.00", ann);
        spend("Aldi", "groceries", "62.40", ann);
        spend("Shell", "gas", "1234.00", null);

        service(APRIL_1_8AM).sendLastMonth();

        assertThat(notifier.sent()).containsExactly(new Notification("Centry: March 2026 summary", """
                Total spent: $1,746.40

                Budgets
                Dining $0.00 / $200.50 (0%)
                Groceries $512.40 / $600 (85%)
                Not budgeted $1,234.00

                Top merchants
                Shell $1,234.00
                Costco $450.00
                Aldi $62.40

                By member
                Unassigned $1,234.00
                Ann $512.40""", Notification.Priority.LOW, List.of("bar_chart")));
    }

    @Test
    void omitsSectionsThatHaveNothingToShow() {
        spend("Shell", "gas", "5.00", null);

        service(APRIL_1_8AM).sendLastMonth();

        assertThat(notifier.sent()).singleElement().extracting(Notification::body).isEqualTo("""
                Total spent: $5.00

                Not budgeted $5.00

                Top merchants
                Shell $5.00

                By member
                Unassigned $5.00""");
    }

    @Test
    void emptyMonthMessage() {
        service(APRIL_1_8AM).sendLastMonth();

        assertThat(notifier.sent()).containsExactly(new Notification("Centry: March 2026 summary",
                "No expenses recorded in March 2026", Notification.Priority.LOW, List.of("bar_chart")));
    }

    @Test
    void januaryDigestSummarisesDecemberOfTheYearBefore() {
        service(LocalDateTime.of(2026, 1, 1, 8, 0)).sendLastMonth();

        assertThat(notifier.sent()).singleElement().extracting(Notification::title)
                .isEqualTo("Centry: December 2025 summary");
    }

    @Test
    void sendsOncePerPeriod() {
        DigestService service = service(APRIL_1_8AM);

        service.sendLastMonth();
        service.sendLastMonth();
        service(APRIL_1_8AM.plusHours(1)).sendLastMonth();

        assertThat(notifier.sent()).hasSize(1);
        assertThat(rows()).isEqualTo(1);
        assertThat(notifiedAt("2026-03")).contains("2026-04-01T08:00:00");
    }

    @Test
    void failureLeavesNotifiedAtNullAndRetrySends() {
        notifier.failing(true);
        service(APRIL_1_8AM).sendLastMonth();
        assertThat(notifiedAt("2026-03").orElse(null)).isNull();

        notifier.failing(false);
        service(APRIL_1_8AM.plusMinutes(5)).retryUnsent();

        assertThat(notifier.sent()).hasSize(2);
        assertThat(notifiedAt("2026-03")).contains("2026-04-01T08:05:00");

        service(APRIL_1_8AM.plusMinutes(10)).retryUnsent();
        assertThat(notifier.sent()).hasSize(2);
    }

    @Test
    void retryWindowIsOneMinuteToTwentyFourHoursInclusive() {
        notifier.failing(true);
        service(APRIL_1_8AM).sendLastMonth();
        notifier.failing(false);

        service(APRIL_1_8AM.plusSeconds(59)).retryUnsent();
        assertThat(notifier.sent()).hasSize(1);

        service(APRIL_1_8AM.plusHours(24)).retryUnsent();
        assertThat(notifier.sent()).hasSize(2);
    }

    @Test
    void abandonsAfter24HoursAndLogsOnce(CapturedOutput output) {
        notifier.failing(true);
        service(APRIL_1_8AM).sendLastMonth();
        notifier.failing(false);
        DigestService late = service(APRIL_1_8AM.plusHours(24).plusSeconds(1));

        late.retryUnsent();
        late.retryUnsent();

        assertThat(notifier.sent()).hasSize(1);
        assertThat(notifiedAt("2026-03").orElse(null)).isNull();
        assertThat(output.getAll().split("Abandoning digest 2026-03", -1)).hasSize(2);
    }

    @Test
    void noNotifierMarksNotified() {
        service(APRIL_1_8AM.plusHours(3), new Notifier[0]).sendLastMonth();

        assertThat(notifiedAt("2026-03")).contains("2026-04-01T11:00:00");
    }

    @Test
    void catchUpWaitsForTheScheduledTime() {
        service(LocalDateTime.of(2026, 4, 1, 7, 59)).catchUp();

        assertThat(notifier.sent()).isEmpty();
        assertThat(rows()).isZero();
    }

    @Test
    void catchUpSendsAMissedDigestWithinThreeDays() {
        service(LocalDateTime.of(2026, 4, 3, 7, 59)).catchUp();

        assertThat(notifier.sent()).singleElement().extracting(Notification::title)
                .isEqualTo("Centry: March 2026 summary");
    }

    @Test
    void catchUpDoesNothingAfterThreeDays() {
        service(LocalDateTime.of(2026, 4, 4, 8, 0)).catchUp();

        assertThat(notifier.sent()).isEmpty();
    }

    @Test
    void catchUpDoesNotResendAnExistingDigest() {
        service(APRIL_1_8AM).sendLastMonth();

        service(APRIL_1_8AM.plusHours(2)).catchUp();

        assertThat(notifier.sent()).hasSize(1);
    }
}
