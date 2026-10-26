package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import com.centry.model.MonthSummary;
import com.centry.model.MonthSummary.CategorySpend;
import com.centry.model.MonthSummary.MemberSpend;
import com.centry.model.MonthSummary.MerchantSpend;
import com.centry.model.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DigestQueryTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);
    private static final LocalDateTime MID_MARCH = LocalDateTime.of(2026, 3, 15, 12, 0);

    @Autowired
    private DigestQuery query;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM budget").update();
        jdbc.sql("DELETE FROM users WHERE username LIKE 'dq-%'").update();
    }

    private void spend(String merchant, String category, String amount, LocalDateTime at, Long spentBy) {
        expenses.save(new ExpenseEntity(null, UUID.randomUUID().toString(), merchant, new BigDecimal(amount),
                category, at, spentBy, null));
    }

    private void spend(String merchant, String category, String amount) {
        spend(merchant, category, amount, MID_MARCH, null);
    }

    @Test
    void onlyLastMonthInHouseholdZone() {
        // Timestamps are stored as household-local time, so month bounds are plain local midnights.
        spend("A", "food", "1.00", LocalDateTime.of(2026, 2, 28, 23, 59, 59), null);
        spend("A", "food", "2.00", LocalDateTime.of(2026, 3, 1, 0, 0, 0), null);
        spend("A", "food", "4.00", LocalDateTime.of(2026, 3, 31, 23, 30), null);
        spend("A", "food", "8.00", LocalDateTime.of(2026, 4, 1, 0, 10), null);

        assertThat(query.forMonth(MARCH).totalCents()).isEqualTo(600);
        assertThat(query.forMonth(YearMonth.of(2026, 4)).totalCents()).isEqualTo(800);
    }

    @Test
    void emptyMonthHasZeroTotalAndEmptyLists() {
        assertThat(query.forMonth(MARCH)).isEqualTo(
                new MonthSummary(MARCH, 0, java.util.List.of(), 0, java.util.List.of(), java.util.List.of()));
    }

    @Test
    void perCategoryVsBudget() {
        budgets.upsert("groceries", new BigDecimal("600.00"), 80);
        budgets.upsert("dining", new BigDecimal("200.50"), 80);
        spend("Costco", "Groceries", "500.00");
        spend("Aldi", " groceries ", "12.40");

        assertThat(query.forMonth(MARCH).budgeted()).containsExactly(
                new CategorySpend("dining", 0, 20050),
                new CategorySpend("groceries", 51240, 60000));
    }

    @Test
    void unbudgetedGrouped() {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        spend("A", "food", "10.00");
        spend("B", "gadgets", "20.00");
        spend("C", null, "5.00");

        MonthSummary summary = query.forMonth(MARCH);

        assertThat(summary.unbudgetedCents()).isEqualTo(2500);
        assertThat(summary.totalCents()).isEqualTo(3500);
    }

    @Test
    void topThreeMerchants() {
        spend("Costco", "x", "50.00");
        spend("costco ", "x", "25.00");
        spend("Aldi", "x", "60.00");
        spend("Shell", "x", "30.00");
        spend("Cafe", "x", "10.00");
        spend(null, "x", "999.00");
        spend("  ", "x", "999.00");

        assertThat(query.forMonth(MARCH).topMerchants()).containsExactly(
                new MerchantSpend("Costco", 7500), new MerchantSpend("Aldi", 6000), new MerchantSpend("Shell", 3000));
    }

    @Test
    void perMemberIncludingUnassigned() {
        long ann = users.insert("dq-ann", "Ann", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();
        long bob = users.insert("dq-bob", "Bob", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();
        spend("A", "x", "10.00", MID_MARCH, ann);
        spend("A", "x", "30.00", MID_MARCH, bob);
        spend("A", "x", "5.00", MID_MARCH, bob);
        spend("A", "x", "2.00", MID_MARCH, null);

        assertThat(query.forMonth(MARCH).byMember()).containsExactly(
                new MemberSpend("Bob", 3500), new MemberSpend("Ann", 1000), new MemberSpend("Unassigned", 200));
    }
}
