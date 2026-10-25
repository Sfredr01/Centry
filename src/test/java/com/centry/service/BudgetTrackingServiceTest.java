package com.centry.service;

import com.centry.config.BudgetConfigProperties;
import com.centry.model.BudgetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BudgetTrackingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-11-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

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

        budgetTrackingService = new BudgetTrackingService(properties, CLOCK);
    }

    @Test
    void testRecordExpenseOk() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(50.00), NOW);
        assertNotNull(status);
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
        assertEquals(0, new BigDecimal("50.00").compareTo(status.getCurrentSpend()));
    }

    @Test
    void testRecordExpenseWarningAndLimit() {
        // First push to warning (>= 80.00)
        BudgetStatus status1 = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(85.00), NOW);
        assertEquals(BudgetStatus.Status.WARNING_EXCEEDED, status1.getStatus());

        // Push past limit (>= 100.00)
        BudgetStatus status2 = budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(20.00), NOW); // total 105.00
        assertEquals(BudgetStatus.Status.LIMIT_EXCEEDED, status2.getStatus());
    }

    @Test
    void nullCategoryOrAmountReturnsNullAndRecordsNothing() {
        assertNull(budgetTrackingService.recordExpense(null, BigDecimal.TEN, NOW));
        assertNull(budgetTrackingService.recordExpense("dining", null, NOW));
        assertEquals(0, BigDecimal.ZERO.compareTo(budgetTrackingService.getCurrentTotal("dining")));
    }

    @Test
    void unconfiguredCategoryIsOkWithNullLimits() {
        BudgetStatus status = budgetTrackingService.recordExpense("travel", BigDecimal.valueOf(500), NOW);
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
        BudgetTrackingService service = new BudgetTrackingService(properties, CLOCK);

        BudgetStatus status = service.recordExpense("misc", BigDecimal.valueOf(999), NOW);
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
        assertNull(status.getMonthlyLimit());
        assertNull(status.getWarningAmount());
    }

    @Test
    void exactlyAtWarningThresholdIsWarning() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", new BigDecimal("80.00"), NOW);
        assertEquals(BudgetStatus.Status.WARNING_EXCEEDED, status.getStatus());
        assertEquals(0, new BigDecimal("80").compareTo(status.getWarningAmount()));
        assertEquals(0, new BigDecimal("100").compareTo(status.getMonthlyLimit()));
    }

    @Test
    void justBelowWarningThresholdIsOk() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", new BigDecimal("79.99"), NOW);
        assertEquals(BudgetStatus.Status.OK, status.getStatus());
    }

    @Test
    void exactlyAtLimitIsLimitExceeded() {
        BudgetStatus status = budgetTrackingService.recordExpense("dining", new BigDecimal("100.00"), NOW);
        assertEquals(BudgetStatus.Status.LIMIT_EXCEEDED, status.getStatus());
    }

    @Test
    void categoryIsNormalizedForTotalsButReportedAsGiven() {
        budgetTrackingService.recordExpense("  Dining ", BigDecimal.valueOf(60), NOW);
        BudgetStatus status = budgetTrackingService.recordExpense("DINING", BigDecimal.valueOf(25), NOW);

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
        budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(10), NOW);
        budgetTrackingService.recordExpense("dining", BigDecimal.valueOf(5), NOW);
        assertEquals(0, BigDecimal.valueOf(15).compareTo(budgetTrackingService.getCurrentTotal("dining")));
    }

    @Test
    void backdatedExpenseCountsInItsOwnMonth() {
        LocalDateTime october = LocalDateTime.of(2025, 10, 20, 12, 0);

        BudgetStatus status = budgetTrackingService.recordExpense("dining", new BigDecimal("90.00"), october);

        assertEquals(BudgetStatus.Status.WARNING_EXCEEDED, status.getStatus());
        assertEquals(0, new BigDecimal("90.00").compareTo(status.getCurrentSpend()));
        // November (the clock's month) is untouched.
        assertEquals(0, BigDecimal.ZERO.compareTo(budgetTrackingService.getCurrentTotal("dining")));
        BudgetStatus november = budgetTrackingService.recordExpense("dining", new BigDecimal("10.00"), NOW);
        assertEquals(0, new BigDecimal("10.00").compareTo(november.getCurrentSpend()));
    }

    @Test
    void currentTotalUsesClock() {
        budgetTrackingService.recordExpense("dining", new BigDecimal("12.00"), LocalDateTime.of(2025, 10, 31, 23, 59));
        budgetTrackingService.recordExpense("dining", new BigDecimal("7.00"), LocalDateTime.of(2025, 11, 1, 0, 0));

        assertEquals(0, new BigDecimal("7.00").compareTo(budgetTrackingService.getCurrentTotal("dining")));
    }
}
