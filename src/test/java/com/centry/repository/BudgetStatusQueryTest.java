package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import com.centry.model.BudgetLevel;
import com.centry.model.BudgetStatus;
import com.centry.model.Keys;
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
class BudgetStatusQueryTest {

    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Autowired
    private BudgetStatusQuery query;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private ExpenseRepository expenses;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserRepository users;

    @BeforeEach
    @AfterEach
    void clean() {
        expenses.deleteAll();
        jdbc.sql("DELETE FROM budget").update();
        jdbc.sql("DELETE FROM users WHERE username LIKE 'bsq-%'").update();
    }

    private void spend(String category, String amount, LocalDateTime at) {
        expenses.save(new ExpenseEntity(null, UUID.randomUUID().toString(), "Shop", new BigDecimal(amount),
                category, at, null, null));
    }

    private BudgetStatus status(String key, YearMonth month) {
        return query.status(key, month).orElseThrow();
    }

    @Test
    void onlyCountsExpensesInTheGivenMonth() {
        budgets.upsert("food", new BigDecimal("1000.00"), 80);
        spend("food", "1.00", LocalDateTime.of(2026, 2, 28, 23, 59, 59));
        spend("food", "2.00", LocalDateTime.of(2026, 3, 1, 0, 0, 0));
        spend("food", "4.00", LocalDateTime.of(2026, 3, 31, 23, 59, 59));
        spend("food", "8.00", LocalDateTime.of(2026, 4, 1, 0, 0, 0));

        assertThat(status("food", MARCH).spent()).isEqualByComparingTo("6.00");
        assertThat(status("food", YearMonth.of(2026, 2)).spent()).isEqualByComparingTo("1.00");
        assertThat(status("food", YearMonth.of(2026, 4)).spent()).isEqualByComparingTo("8.00");
        assertThat(status("food", YearMonth.of(2026, 5)).spent()).isEqualByComparingTo("0.00");
    }

    @Test
    void matchesCategoryCaseAndWhitespaceInsensitively() {
        budgets.upsert("groceries", new BigDecimal("1000.00"), 80);
        LocalDateTime at = LocalDateTime.of(2026, 3, 10, 12, 0);
        spend("Groceries", "1.00", at);
        spend("  GROCERIES ", "2.00", at);
        spend("GrOcErIeS", "4.00", at);
        spend("groceries", "8.00", at);
        spend("groceries store", "16.00", at);
        spend("other", "32.00", at);

        assertThat(status("groceries", MARCH).spent()).isEqualByComparingTo("15.00");
    }

    // SQLite lower() folds ASCII only, Java's toLowerCase also folds other scripts (documented in V2).
    @Test
    void nonAsciiCaseIsNotFolded() {
        budgets.upsert("café", new BigDecimal("1000.00"), 80);
        LocalDateTime at = LocalDateTime.of(2026, 3, 10, 12, 0);
        spend("café", "1.00", at);
        spend("CAFÉ", "2.00", at);

        assertThat(status("café", MARCH).spent()).isEqualByComparingTo("1.00");
    }

    // Production keys every name with Keys.of; it must agree with SQL's lower(trim()) or spend silently goes missing.
    @Test
    void keysOfMatchesSqlForNonAsciiAndTabbedNames() {
        LocalDateTime at = LocalDateTime.of(2026, 3, 10, 12, 0);
        budgets.upsert(Keys.of("Épicerie"), new BigDecimal("1000.00"), 80);
        budgets.upsert(Keys.of("\tfood"), new BigDecimal("1000.00"), 80);
        spend("Épicerie", "3.00", at);
        spend("\tfood", "4.00", at);

        assertThat(status(Keys.of("Épicerie"), MARCH).spent()).isEqualByComparingTo("3.00");
        assertThat(status(Keys.of("\tfood"), MARCH).spent()).isEqualByComparingTo("4.00");
    }

    @Test
    void noBudgetMeansNoStatus() {
        spend("food", "50.00", LocalDateTime.of(2026, 3, 10, 12, 0));

        assertThat(query.status("food", MARCH)).isEmpty();
    }

    @Test
    void levelBoundariesAtWarnAndLimit() {
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        LocalDateTime at = LocalDateTime.of(2026, 3, 10, 12, 0);

        spend("food", "79.99", at);
        assertThat(status("food", MARCH).level()).isEqualTo(BudgetLevel.OK);
        spend("food", "0.01", at);
        assertThat(status("food", MARCH).level()).isEqualTo(BudgetLevel.WARNING_EXCEEDED);
        spend("food", "19.99", at);
        assertThat(status("food", MARCH).level()).isEqualTo(BudgetLevel.WARNING_EXCEEDED);
        spend("food", "0.01", at);
        BudgetStatus limit = status("food", MARCH);
        assertThat(limit.level()).isEqualTo(BudgetLevel.LIMIT_EXCEEDED);
        assertThat(limit.category()).isEqualTo("food");
        assertThat(limit.monthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(limit.warnPercent()).isEqualTo(80);
        assertThat(limit.warningAmount()).isEqualByComparingTo("80.00");
        assertThat(limit.spent()).isEqualByComparingTo("100.00");
    }

    // Warning amount is rounded up to a whole cent, which is exactly "spent >= limit * pct / 100" for cents.
    @Test
    void warningAmountRoundsUpToWholeCent() {
        budgets.upsert("odd", new BigDecimal("10.01"), 80);
        LocalDateTime at = LocalDateTime.of(2026, 3, 10, 12, 0);

        spend("odd", "8.00", at);
        BudgetStatus below = status("odd", MARCH);
        assertThat(below.warningAmount()).isEqualByComparingTo("8.01");
        assertThat(below.level()).isEqualTo(BudgetLevel.OK);
        spend("odd", "0.01", at);
        assertThat(status("odd", MARCH).level()).isEqualTo(BudgetLevel.WARNING_EXCEEDED);
    }

    // A limit of one cent: the rounded warning amount equals the limit, so the limit level wins.
    @Test
    void limitWinsWhenWarningRoundsUpToLimit() {
        budgets.upsert("tiny", new BigDecimal("0.01"), 80);
        spend("tiny", "0.01", LocalDateTime.of(2026, 3, 10, 12, 0));

        assertThat(status("tiny", MARCH).level()).isEqualTo(BudgetLevel.LIMIT_EXCEEDED);
    }

    @Test
    void filtersByMember() {
        long ann = users.insert("bsq-ann", "Ann", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();
        long bob = users.insert("bsq-bob", "Bob", null, Role.MEMBER, LocalDateTime.of(2026, 1, 1, 0, 0)).id();
        budgets.upsert("food", new BigDecimal("100.00"), 80);
        LocalDateTime at = LocalDateTime.of(2026, 3, 10, 12, 0);
        expenses.save(new ExpenseEntity(null, "a1", "Shop", new BigDecimal("10.00"), "food", at, ann, null));
        expenses.save(new ExpenseEntity(null, "b1", "Shop", new BigDecimal("30.00"), "food", at, bob, null));
        // Unattributed spend belongs to nobody's filter, only to Everyone.
        expenses.save(new ExpenseEntity(null, "n1", "Shop", new BigDecimal("5.00"), "food", at, null, null));

        BudgetStatus annOnly = query.all(MARCH, ann).get(0);

        assertThat(annOnly.spent()).isEqualByComparingTo("10.00");
        // The limit is the household's, whoever is selected.
        assertThat(annOnly.monthlyLimit()).isEqualByComparingTo("100.00");
        assertThat(query.all(MARCH, bob).get(0).spent()).isEqualByComparingTo("30.00");
        assertThat(query.all(MARCH, null).get(0).spent()).isEqualByComparingTo("45.00");
        assertThat(query.all(MARCH).get(0).spent()).isEqualByComparingTo("45.00");
    }
}
