package com.centry.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BudgetRepositoryTest {

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM budget").update();
    }

    @Test
    void categoryKeysAreSortedAndEmptyWithoutBudgets() {
        assertThat(budgets.categoryKeys()).isEmpty();

        budgets.upsert("travel", new BigDecimal("10.00"), 80);
        budgets.upsert("food", new BigDecimal("10.00"), 80);

        assertThat(budgets.categoryKeys()).containsExactly("food", "travel");
    }
}
