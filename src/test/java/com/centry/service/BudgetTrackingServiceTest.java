package com.centry.service;

import com.centry.config.BudgetConfigProperties;
import com.centry.model.BudgetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Month rollover is intentionally not covered: the service reads YearMonth.now() directly,
 * so the clock is not injectable (known issue, tracked for a later milestone).
 */
class BudgetTrackingServiceTest {

    private BudgetTrackingService budgetTrackingService;

    @BeforeEach
    void setUp() {
        BudgetConfigProperties properties = new BudgetConfigProperties();
        Map<String, BudgetConfigProperties.CategoryLimit> categories = new HashMap<>();

        BudgetConfigProperties.CategoryLimit limit = new BudgetConfigProperties.CategoryLimit();
        limit.setMonthlyLimit(BigDecimal.valueOf(100.00));
        limit.setWarningThresholdPercent(0.80); // warning at 80.00

        categories.put("dining", limit);
        properties.setCategories(categories);

        budgetTrackingService = new BudgetTrackingService(properties);
    }

    @Test
    void testRecordExpenseOk() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(50.00));
        assertNotNull(status);
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
        assertEquals(0, new BigDecimal("50.00").compareTo(status.getCurrentSpend()));
    }

    @Test
    void testRecordExpenseWarningAndLimit() {
        // First push to warning (>= 80.00)
        BudgetStatus status1 = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(85.00));
        assertEquals(BudgetStatus.Status.WARNING_EXCEEDED, status1.getStatus());

        // Push past limit (>= 100.00)
        BudgetStatus status2 = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(20.00)); // total 105.00
        assertEquals(BudgetStatus.Status.LIMIT_EXCEEDED, status2.getStatus());
    }

    @Test
    void nullCategoryOrAmountReturnsNullAndRecordsNothing() {
        assertNull(budgetTrackingService.recordExpense(null, BigDecimal.TEN));
        assertNull(budgetTrackingService.recordExpense("dining", null));
        assertEquals(0, BigDecimal.ZERO.compareTo(budgetTrackingService.getCurrentTotal("dining")));
    }

    @Test
    void unconfiguredCategoryIsOkWithNullLimits() {
        BudgetStatus status = budgetTrackingService.recordExpense("travel", BigDecimal.valueOf(500));
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
        assertEquals("travel", status.getCategory());
        assertNull(status.getMonthlyLimit());
        assertNull(status.getWarningAmount());
        assertEquals(0, BigDecimal.valueOf(500).compareTo(status.getCurrentSpend()));
    }

    @Test
    void configuredCategoryWithNullMonthlyLimitIsOk() {
        BudgetConfigProperties properties = new BudgetConfigProperties();
        properties.setCategories(Map.of("misc", new BudgetConfigProperties.CategoryLimit()));
        BudgetTrackingService service = new BudgetTrackingService(properties);

        BudgetStatus status = service.recordExpense("misc", BigDecimal.valueOf(999));
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
        assertNull(status.getMonthlyLimit());
        assertNull(status.getWarningAmount());
    }

    @Test
    void exactlyAtWarningThresholdIsWarning() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", new BigDecimal("80.00"));
        assertEquals(BudgetStatus.Status.WARNING_EXCEEDED, status.getStatus());
        assertEquals(0, new BigDecimal("80").compareTo(status.getWarningAmount()));
        assertEquals(0, new BigDecimal("100").compareTo(status.getMonthlyLimit()));
    }

    @Test
    void justBelowWarningThresholdIsOk() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", new BigDecimal("79.99"));
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
    }

    @Test
    void exactlyAtLimitIsLimitExceeded() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", new BigDecimal("100.00"));
        assertEquals(BudgetStatus.Status.LIMIT_EXCEEDED, status.getStatus());
    }

    @Test
    void categoryIsNormalizedForTotalsButReportedAsGiven() {
        budgetTrackingService.recordExpense("  Dining ", BigDecimal.valueOf(60));
        BudgetStatus status = budgetTrackingService.recordExpense("DINING", BigDecimal.valueOf(25));

        assertEquals("DINING", status.getCategory());
        assertEquals(0, BigDecimal.valueOf(85).compareTo(status.getCurrentSpend()));
        assertEquals(BudgetStatus.Status.WARNING_EXCEEDED, status.getStatus());
        assertEquals(0, BigDecimal.valueOf(85).compareTo(budgetTrackingService.getCurrentTotal(" dInInG")));
    }

    @Test
    void getCurrentTotalIsZeroWhenNothingRecorded() {
        assertEquals(0, BigDecimal.ZERO.compareTo(budgetTrackingService.getCurrentTotal("dining")));
    }

    @Test
    void getCurrentTotalSumsRecordedExpenses() {
        budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(10));
        budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(5));
        assertEquals(0, BigDecimal.valueOf(15).compareTo(budgetTrackingService.getCurrentTotal("dining")));
    }
}
