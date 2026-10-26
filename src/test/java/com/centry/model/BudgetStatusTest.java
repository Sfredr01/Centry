package com.centry.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetStatusTest {

    @Test
    void worstFirstOrdersByLevelThenRatioThenName() {
        BudgetStatus a = BudgetStatus.of("a", 10000, 80, 5000); // OK, 50%
        BudgetStatus b = BudgetStatus.of("b", 10000, 80, 9000); // WARN, 90%
        BudgetStatus c = BudgetStatus.of("c", 5000, 80, 4500);  // WARN, 90%
        BudgetStatus d = BudgetStatus.of("d", 10000, 80, 8500); // WARN, 85%
        BudgetStatus e = BudgetStatus.of("e", 1000, 80, 2000);  // LIMIT, 200%
        BudgetStatus f = BudgetStatus.of("f", 10000, 80, 10000); // LIMIT, 100%
        BudgetStatus g = BudgetStatus.of("g", 10000, 80, 0);    // OK, 0%

        List<BudgetStatus> sorted = List.of(g, c, a, f, d, b, e).stream().sorted(BudgetStatus.WORST_FIRST).toList();

        assertThat(sorted).extracting(BudgetStatus::category).containsExactly("e", "f", "b", "c", "d", "a", "g");
    }
}
