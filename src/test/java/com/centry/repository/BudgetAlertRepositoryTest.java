package com.centry.repository;

import com.centry.entity.BudgetAlertEntity;
import com.centry.model.BudgetLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BudgetAlertRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 3, 10, 12, 0);

    @Autowired
    private BudgetAlertRepository alerts;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
    }

    private static BudgetAlertEntity alert(String category, BudgetLevel level) {
        return new BudgetAlertEntity(null, category, new BigDecimal("90.00"), new BigDecimal("100.00"),
                new BigDecimal("80.00"), level, "msg", AT);
    }

    @Test
    void duplicateInsertIgnored() {
        YearMonth march = YearMonth.of(2026, 3);

        var first = alerts.insertIfAbsent(alert("food", BudgetLevel.WARNING_EXCEEDED), march);
        assertThat(first).isPresent();
        assertThat(first.getAsLong()).isEqualTo(alerts.findTop50ByOrderByTimestampDesc().get(0).id());
        assertThat(alerts.insertIfAbsent(alert("food", BudgetLevel.WARNING_EXCEEDED), march)).isEmpty();
        assertThat(alerts.insertIfAbsent(alert("  FOOD ", BudgetLevel.WARNING_EXCEEDED), march)).isEmpty();

        // A different level, month or category is a different alert.
        assertThat(alerts.insertIfAbsent(alert("food", BudgetLevel.LIMIT_EXCEEDED), march)).isPresent();
        assertThat(alerts.insertIfAbsent(alert("food", BudgetLevel.WARNING_EXCEEDED), YearMonth.of(2026, 4))).isPresent();
        assertThat(alerts.insertIfAbsent(alert("rent", BudgetLevel.WARNING_EXCEEDED), march)).isPresent();

        assertThat(alerts.findTop50ByOrderByTimestampDesc()).hasSize(4);
    }
}
