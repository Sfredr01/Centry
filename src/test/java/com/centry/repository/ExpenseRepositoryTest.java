package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ExpenseRepositoryTest {

    private static final LocalDateTime TS = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Autowired
    private ExpenseRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private ExpenseEntity expense(String category, String amount) {
        return new ExpenseEntity(null, "tx-" + category + amount, "m", new BigDecimal(amount), category, TS);
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

        assertThat(repository.findRecent(3)).containsExactly(tieLast, tieFirst, older);
        assertThat(repository.findRecent(10)).hasSize(4);
    }

    @Test
    void recentCategoriesAreDistinctByKeyNewestFirstAndKeepLatestSpelling() {
        repository.save(new ExpenseEntity(null, "1", "m", new BigDecimal("1.00"), "Dining", TS));
        repository.save(new ExpenseEntity(null, "2", "m", new BigDecimal("1.00"), "dining ", TS));
        repository.save(new ExpenseEntity(null, "3", "m", new BigDecimal("1.00"), "Fuel", TS));
        repository.save(new ExpenseEntity(null, "4", "m", new BigDecimal("1.00"), "Gifts", TS));

        assertThat(repository.recentCategories(10)).containsExactly("Gifts", "Fuel", "dining ");
        assertThat(repository.recentCategories(2)).containsExactly("Gifts", "Fuel");
    }

    private ExpenseEntity at(String id, LocalDateTime when) {
        return new ExpenseEntity(null, id, "m", new BigDecimal("1.00"), "dining", when);
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
}
