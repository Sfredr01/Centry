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
        assertThat(repository.sumAmountByCategory().get("dining")).isEqualTo(new BigDecimal("0.30"));
    }

    @Test
    void sumAmountByCategoryGroupsAndSums() {
        repository.save(expense("dining", "10.50"));
        repository.save(expense("dining", "4.50"));
        repository.save(expense("travel", "200.00"));

        assertThat(repository.sumAmountByCategory())
                .hasSize(2)
                .containsEntry("dining", new BigDecimal("15.00"))
                .containsEntry("travel", new BigDecimal("200.00"));
    }

    @Test
    void sumAmountByCategoryIsEmptyWithNoExpenses() {
        assertThat(repository.sumAmountByCategory()).isEmpty();
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
