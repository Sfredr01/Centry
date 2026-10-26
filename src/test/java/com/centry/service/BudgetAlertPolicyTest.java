package com.centry.service;

import com.centry.dto.ExpenseEvent;
import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetAlertEvent;
import com.centry.model.BudgetLevel;
import com.centry.repository.BudgetAlertRepository;
import com.centry.repository.BudgetRepository;
import com.centry.repository.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Behavior of the alert policy end to end: real SQLite, real transaction, alerts observed as published events.
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class BudgetAlertPolicyTest {

    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 10, 12, 0);
    private static final LocalDateTime FEB = LocalDateTime.of(2026, 2, 10, 12, 0);

    @Autowired
    private ExpenseService service;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private BudgetAlertRepository alerts;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationEvents applicationEvents;

    @BeforeEach
    void setUp() {
        expenses.deleteAll();
        alerts.deleteAll();
        budgets.upsert("policy", new BigDecimal("100.00"), 80);
    }

    private void spend(String amount, LocalDateTime at) {
        service.record(ExpenseEvent.builder().merchant("Shop").amount(new BigDecimal(amount))
                .category("Policy").timestamp(at).build(), null);
    }

    private List<BudgetLevel> levels() {
        return applicationEvents.stream(BudgetAlertEvent.class).map(BudgetAlertEvent::getAlertLevel).toList();
    }

    private List<String> persistedPeriods() {
        return jdbc.sql("SELECT period FROM budget_alert ORDER BY id").query(String.class).list();
    }

    @Test
    void noAlertBelowWarn() {
        spend("79.99", JAN);

        assertThat(levels()).isEmpty();
        assertThat(alerts.findTop50ByOrderByTimestampDesc()).isEmpty();
    }

    @Test
    void warnOnceOnCrossing() {
        spend("50.00", JAN);
        spend("30.00", JAN);
        spend("1.00", JAN);
        spend("1.00", JAN);

        assertThat(levels()).containsExactly(BudgetLevel.WARNING_EXCEEDED);
        assertThat(alerts.findTop50ByOrderByTimestampDesc()).hasSize(1);
    }

    @Test
    void exceededOnceAt100() {
        spend("85.00", JAN);
        spend("15.00", JAN);
        spend("5.00", JAN);
        spend("5.00", JAN);

        assertThat(levels()).containsExactly(BudgetLevel.WARNING_EXCEEDED, BudgetLevel.LIMIT_EXCEEDED);
        assertThat(alerts.findTop50ByOrderByTimestampDesc()).hasSize(2);
    }

    @Test
    void jumpPastBothEmitsExceededOnly() {
        spend("120.00", JAN);

        assertThat(levels()).containsExactly(BudgetLevel.LIMIT_EXCEEDED);
        BudgetAlertEvent event = applicationEvents.stream(BudgetAlertEvent.class).findFirst().orElseThrow();
        assertThat(event.getCategory()).isEqualTo("policy");
        assertThat(event.getCurrentSpend()).isEqualByComparingTo("120.00");
        assertThat(event.getMonthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(event.getWarningThreshold()).isEqualByComparingTo("80.00");
        assertThat(event.getMessage())
                .isEqualTo("Budget alert for [policy]: Current spend $120.00 exceeds monthly limit of $100.00");
        List<BudgetAlertEntity> rows = alerts.findTop50ByOrderByTimestampDesc();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(BudgetLevel.LIMIT_EXCEEDED);
            assertThat(row.message()).isEqualTo(event.getMessage());
        });
    }

    @Test
    void eventCarriesThePersistedAlertId() {
        spend("85.00", JAN);

        Long rowId = alerts.findTop50ByOrderByTimestampDesc().get(0).id();
        assertThat(applicationEvents.stream(BudgetAlertEvent.class).findFirst().orElseThrow().getAlertId())
                .isEqualTo(rowId);
    }

    @Test
    void warningMessageMentionsWarningThreshold() {
        spend("85.00", JAN);

        assertThat(applicationEvents.stream(BudgetAlertEvent.class).findFirst().orElseThrow().getMessage())
                .isEqualTo("Budget alert for [policy]: Current spend $85.00 exceeds warning threshold of $80.00");
    }

    @Test
    void backdatedAlertsItsOwnMonth() {
        spend("90.00", JAN);
        // Recorded later but belongs to December: its own month is evaluated, not the latest one.
        spend("90.00", LocalDateTime.of(2025, 12, 20, 9, 0));
        // Another backdated one in December is a repeat for that month.
        spend("1.00", LocalDateTime.of(2025, 12, 21, 9, 0));

        assertThat(levels()).containsExactly(BudgetLevel.WARNING_EXCEEDED, BudgetLevel.WARNING_EXCEEDED);
        assertThat(persistedPeriods()).containsExactly("2026-01", "2025-12");
    }

    @Test
    void newMonthResets() {
        spend("90.00", JAN);
        spend("90.00", FEB);
        spend("1.00", FEB);

        assertThat(levels()).containsExactly(BudgetLevel.WARNING_EXCEEDED, BudgetLevel.WARNING_EXCEEDED);
        assertThat(persistedPeriods()).containsExactly("2026-01", "2026-02");
    }

    @Test
    void categoryWithoutBudgetNeverAlerts() {
        service.record(ExpenseEvent.builder().merchant("Shop").amount(new BigDecimal("5000.00"))
                .category("unbudgeted").timestamp(JAN).build(), null);

        assertThat(levels()).isEmpty();
    }
}
