package com.centry.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BudgetStatusTest {

    @Test
    void constructorPopulatesAllFields() {
        BigDecimal spend = new BigDecimal("50");
        BigDecimal limit = new BigDecimal("100");
        BigDecimal warn = new BigDecimal("80");
        BudgetStatus s = new BudgetStatus("food", spend, limit, warn, BudgetStatus.Status.OK);

        assertEquals("food", s.getCategory());
        assertSame(spend, s.getCurrentSpend());
        assertSame(limit, s.getMonthlyLimit());
        assertSame(warn, s.getWarningAmount());
        assertEquals(BudgetStatus.Status.OK, s.getStatus());
    }

    @Test
    void statusEnumValuesAndOrder() {
        assertArrayEquals(
                new BudgetStatus.Status[] {BudgetStatus.Status.OK, BudgetStatus.Status.WARNING_EXCEEDED,
                        BudgetStatus.Status.LIMIT_EXCEEDED},
                BudgetStatus.Status.values());
        assertEquals(BudgetStatus.Status.LIMIT_EXCEEDED, BudgetStatus.Status.valueOf("LIMIT_EXCEEDED"));
    }
}
