package com.centry.persistence;

import com.centry.CentryApplication;
import com.centry.entity.ExpenseEntity;
import com.centry.model.BudgetLevel;
import com.centry.model.BudgetStatus;
import com.centry.repository.BudgetRepository;
import com.centry.repository.BudgetStatusQuery;
import com.centry.repository.ExpenseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceTest {

    @TempDir
    Path dir;

    private ConfigurableApplicationContext start() {
        return new SpringApplication(CentryApplication.class).run(
                "--spring.profiles.active=test",
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=jdbc:sqlite:" + dir.resolve("data").resolve("centry.db"));
    }

    @Test
    void dataSurvivesContextRestart() {
        try (ConfigurableApplicationContext first = start()) {
            first.getBean(ExpenseRepository.class).save(new ExpenseEntity(
                    null, "tx-restart", "Cafe", new BigDecimal("4.50"), "dining", LocalDateTime.of(2026, 1, 2, 3, 4)));
        }

        try (ConfigurableApplicationContext second = start()) {
            assertThat(second.getBean(ExpenseRepository.class).findAll())
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.transactionId()).isEqualTo("tx-restart");
                        assertThat(e.amount()).isEqualTo(new BigDecimal("4.50"));
                    });
        }
    }

    // Budget status is a query over stored rows, so a restart cannot change it.
    @Test
    void budgetStateSurvivesContextRestart() {
        YearMonth january = YearMonth.of(2026, 1);
        BudgetStatus before;
        try (ConfigurableApplicationContext first = start()) {
            first.getBean(BudgetRepository.class).upsert("dining", new BigDecimal("100.00"), 80);
            first.getBean(ExpenseRepository.class).save(new ExpenseEntity(
                    null, "tx-budget", "Cafe", new BigDecimal("85.00"), "Dining", LocalDateTime.of(2026, 1, 2, 3, 4)));
            before = first.getBean(BudgetStatusQuery.class).status("dining", january).orElseThrow();
        }

        try (ConfigurableApplicationContext second = start()) {
            BudgetStatus after = second.getBean(BudgetStatusQuery.class).status("dining", january).orElseThrow();

            assertThat(after).isEqualTo(before);
            assertThat(after.spent()).isEqualByComparingTo("85.00");
            assertThat(after.level()).isEqualTo(BudgetLevel.WARNING_EXCEEDED);
        }
    }
}
