package com.centry.repository;

import com.centry.entity.ExpenseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class ExpenseRepositoryTest {

    @Autowired
    private ExpenseRepository repository;

    private ExpenseEntity expense(String category, String amount) {
        return ExpenseEntity.builder().transactionId("tx-" + category + amount).merchant("m")
                .category(category).amount(new BigDecimal(amount)).build();
    }

    @Test
    void sumAmountByCategoryGroupsAndSums() {
        repository.saveAll(List.of(
                expense("dining", "10.50"), expense("dining", "4.50"), expense("travel", "200.00")));

        List<Object[]> rows = repository.sumAmountByCategory();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo("dining");
            assertThat((BigDecimal) r[1]).isEqualByComparingTo("15.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo("travel");
            assertThat((BigDecimal) r[1]).isEqualByComparingTo("200.00");
        });
    }

    @Test
    void sumAmountByCategoryIsEmptyWithNoExpenses() {
        assertThat(repository.sumAmountByCategory()).isEmpty();
    }

    @Test
    void findByCategoryIgnoreCaseMatchesRegardlessOfCase() {
        repository.saveAll(List.of(
                expense("Dining", "1.00"), expense("dining", "2.00"), expense("travel", "3.00")));

        assertThat(repository.findByCategoryIgnoreCase("DINING"))
                .extracting(ExpenseEntity::getCategory)
                .containsExactlyInAnyOrder("Dining", "dining");
        assertThat(repository.findByCategoryIgnoreCase("unknown")).isEmpty();
    }
}
