package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.centry.model.Role;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ExpenseRepositoryTest {

    private static final LocalDateTime TS = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Autowired
    private ExpenseRepository repository;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        jdbc.sql("DELETE FROM users WHERE username LIKE 'repo-%'").update();
    }

    private long user(String name) {
        return users.insert("repo-" + name, name, null, Role.MEMBER, TS).id();
    }

    private ExpenseEntity expense(String category, String amount) {
        return new ExpenseEntity(null, "tx-" + category + amount, "m", new BigDecimal(amount), category, TS, null, null);
    }

    @Test
    void savesAndReadsBackExactCents() {
        ExpenseEntity a = repository.save(expense("dining", "0.10"));
        repository.save(expense("dining", "0.20"));

        assertThat(a.id()).isNotNull();
        assertThat(repository.count()).isEqualTo(2);
        List<ExpenseEntity> rows = repository.findAll();
        assertThat(rows).extracting(ExpenseEntity::amount)
                .containsExactly(new BigDecimal("0.10"), new BigDecimal("0.20"));
        assertThat(rows.get(0)).isEqualTo(a);
        assertThat(rows.get(0).timestamp()).isEqualTo(TS);
    }

    @Test
    void findRecentOrdersByDateThenInsertionAndHonoursLimit() {
        ExpenseEntity older = repository.save(at("a", TS));
        ExpenseEntity tieFirst = repository.save(at("b", TS.plusDays(2)));
        ExpenseEntity tieLast = repository.save(at("c", TS.plusDays(2)));
        repository.save(at("d", TS.minusDays(5)));

        assertThat(repository.findRecent(3, null)).containsExactly(tieLast, tieFirst, older);
        assertThat(repository.findRecent(10, null)).hasSize(4);
    }

    @Test
    void recentCategoriesAreDistinctByKeyNewestFirstAndKeepLatestSpelling() {
        repository.save(new ExpenseEntity(null, "1", "m", new BigDecimal("1.00"), "Dining", TS, null, null));
        repository.save(new ExpenseEntity(null, "2", "m", new BigDecimal("1.00"), "dining ", TS, null, null));
        repository.save(new ExpenseEntity(null, "3", "m", new BigDecimal("1.00"), "Fuel", TS, null, null));
        repository.save(new ExpenseEntity(null, "4", "m", new BigDecimal("1.00"), "Gifts", TS, null, null));

        assertThat(repository.recentCategories(10)).containsExactly("Gifts", "Fuel", "dining ");
        assertThat(repository.recentCategories(2)).containsExactly("Gifts", "Fuel");
    }

    private ExpenseEntity at(String id, LocalDateTime when) {
        return new ExpenseEntity(null, id, "m", new BigDecimal("1.00"), "dining", when, null, null);
    }

    @Test
    void findsByIdAndByTransactionId() {
        ExpenseEntity saved = repository.save(expense("dining", "10.50"));

        assertThat(repository.findById(saved.id())).contains(saved);
        assertThat(repository.findByTransactionId(saved.transactionId())).contains(saved);
    }

    @Test
    void findsNothingForUnknownKeys() {
        assertThat(repository.findById(999_999L)).isEmpty();
        assertThat(repository.findByTransactionId("nope")).isEmpty();
    }

    @Test
    void duplicateTransactionIdIsReportedAsDuplicateKey() {
        repository.save(expense("dining", "10.50"));

        assertThatThrownBy(() -> repository.save(expense("dining", "10.50")))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void otherConstraintViolationsAreNotDuplicateKeys() {
        assertThatThrownBy(() -> repository.save(expense("dining", "0.00")))
                .isNotInstanceOf(DuplicateKeyException.class)
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsSubCentAmount() {
        assertThatThrownBy(() -> repository.save(expense("dining", "4.505")))
                .isInstanceOf(ArithmeticException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void attributionRoundTripsAndStaysNullWhenUnknown() {
        long ann = user("ann");
        long bob = user("bob");
        ExpenseEntity attributed = repository.save(new ExpenseEntity(null, "a", "m", new BigDecimal("1.00"), "c", TS, ann, bob));
        ExpenseEntity legacy = repository.save(new ExpenseEntity(null, "b", "m", new BigDecimal("1.00"), "c", TS, null, null));

        assertThat(attributed.spentBy()).isEqualTo(ann);
        assertThat(attributed.createdBy()).isEqualTo(bob);
        assertThat(repository.findById(attributed.id()).orElseThrow()).isEqualTo(attributed);
        assertThat(repository.findById(legacy.id()).orElseThrow().spentBy()).isNull();
        assertThat(repository.findById(legacy.id()).orElseThrow().createdBy()).isNull();
    }

    @Test
    void findRecentCanBeScopedToOneMember() {
        long ann = user("ann");
        long bob = user("bob");
        ExpenseEntity a1 = repository.save(new ExpenseEntity(null, "1", "m", new BigDecimal("1.00"), "c", TS, ann, null));
        repository.save(new ExpenseEntity(null, "2", "m", new BigDecimal("1.00"), "c", TS, bob, null));
        ExpenseEntity a2 = repository.save(new ExpenseEntity(null, "3", "m", new BigDecimal("1.00"), "c", TS, ann, null));

        assertThat(repository.findRecent(10, ann)).containsExactly(a2, a1);
        assertThat(repository.findRecent(10, null)).hasSize(3);
    }

    @Test
    void monthTotalSumsEveryCategoryInsideTheMonthOptionallyForOneMember() {
        long ann = user("ann");
        long bob = user("bob");
        LocalDateTime jan1 = LocalDateTime.of(2026, 1, 1, 0, 0);
        repository.save(new ExpenseEntity(null, "1", "m", new BigDecimal("1.10"), "food", jan1, ann, null));
        repository.save(new ExpenseEntity(null, "2", "m", new BigDecimal("2.20"), "fun", TS, bob, null));
        repository.save(new ExpenseEntity(null, "3", "m", new BigDecimal("4.40"), "food", TS, null, null));
        // Either side of January: the last second of December and the first of February do not count.
        repository.save(new ExpenseEntity(null, "4", "m", new BigDecimal("8.00"), "food", jan1.minusSeconds(1), ann, null));
        repository.save(new ExpenseEntity(null, "5", "m", new BigDecimal("16.00"), "food", jan1.plusMonths(1), ann, null));

        assertThat(repository.monthTotal(YearMonth.of(2026, 1), null)).isEqualTo(new BigDecimal("7.70"));
        assertThat(repository.monthTotal(YearMonth.of(2026, 1), ann)).isEqualTo(new BigDecimal("1.10"));
        assertThat(repository.monthTotal(YearMonth.of(2025, 6), null)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void updateChangesEditableFieldsAndKeepsIdentityAndCreator() {
        long ann = user("ann");
        long bob = user("bob");
        ExpenseEntity saved = repository.save(new ExpenseEntity(null, "u", "m", new BigDecimal("1.00"), "c", TS, ann, bob));
        LocalDateTime later = TS.plusDays(3);

        repository.update(new ExpenseEntity(saved.id(), "ignored", "shop", new BigDecimal("9.99"), "food", later,
                bob, ann));

        assertThat(repository.findById(saved.id()).orElseThrow()).isEqualTo(new ExpenseEntity(saved.id(), "u", "shop",
                new BigDecimal("9.99"), "food", later, bob, bob));
    }

    @Test
    void deleteRemovesOnlyThatRow() {
        ExpenseEntity gone = repository.save(expense("dining", "1.00"));
        ExpenseEntity kept = repository.save(expense("dining", "2.00"));

        repository.deleteById(gone.id());

        assertThat(repository.findAll()).containsExactly(kept);
    }
}
